"""Loopback-only fault/route fixtures; never log headers, bodies or credentials."""
import base64
import contextlib
import select
import socket
import socketserver
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import httpx


class HttpProxy(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, base, username='', password='', on_response=None, ssl_context=None):
        self.base = base
        self.origin = urlsplit(base)
        self.authorization = 'Basic ' + base64.b64encode((username + ':' + password).encode()).decode() if username else None
        self.faults = set()
        self.calls = []
        self.on_response = on_response
        super().__init__(('127.0.0.1', 0), HttpHandler)
        self.tls = ssl_context is not None
        if ssl_context:
            self.socket = ssl_context.wrap_socket(self.socket, server_side=True)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f'{"https" if self.tls else "http"}://127.0.0.1:{self.server_port}'

    def close(self):
        self.shutdown()
        self.server_close()
        self.thread.join()


class HttpHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_GET(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        if self.server.authorization and self.headers.get('Proxy-Authorization') != self.server.authorization:
            self.send_response(407)
            self.send_header('Proxy-Authenticate', 'Basic realm="isolated-test"')
            self.send_header('Content-Length', '0')
            self.send_header('Connection', 'close')
            self.end_headers()
            self.close_connection = True
            return
        parsed = urlsplit(self.path)
        expected = self.server.origin
        if (parsed.scheme, parsed.hostname, parsed.port) != (expected.scheme, expected.hostname, expected.port) or not parsed.path.startswith(expected.path + '/'):
            self.send_error(403)
            return
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        headers = {key: value for key, value in self.headers.items() if key.lower() not in ('host', 'proxy-authorization', 'proxy-connection', 'connection', 'content-length')}
        self.server.calls.append((self.command, parsed.path))
        try:
            with httpx.Client(timeout=30, trust_env=False, follow_redirects=False) as client:
                response = client.request(self.command, self.path, headers=headers, content=body)
            if self.server.on_response:
                self.server.on_response(parsed.path, response)
            suffix = next((fault for fault in self.server.faults if parsed.path.endswith('/' + fault)), None)
            if suffix:
                self.server.faults.remove(suffix)
                self.close_connection = True
                with contextlib.suppress(OSError):
                    self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            self.send_response(response.status_code)
            for key, value in response.headers.items():
                if key.lower() not in ('content-length', 'content-encoding', 'transfer-encoding', 'connection'):
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(response.content)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(response.content)
            self.close_connection = True
        except (httpx.HTTPError, OSError):
            self.close_connection = True


def receive(sock, size):
    result = bytearray()
    while len(result) < size:
        data = sock.recv(size - len(result))
        if not data:
            raise OSError('SOCKS_CLOSED')
        result.extend(data)
    return bytes(result)


class SocksProxy(socketserver.ThreadingTCPServer):
    daemon_threads = True

    def __init__(self, host, port, username, password):
        self.destination, self.username, self.password = (host, port), username.encode(), password.encode()
        self.connections = 0
        super().__init__(('127.0.0.1', 0), SocksHandler)
        self.thread = threading.Thread(target=self.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self):
        return f'socks5://127.0.0.1:{self.server_address[1]}'

    def close(self):
        self.shutdown()
        self.server_close()
        self.thread.join()


class SocksHandler(socketserver.BaseRequestHandler):
    def handle(self):
        sock = self.request
        sock.settimeout(10)
        try:
            version, count = receive(sock, 2)
            methods = receive(sock, count)
            if version != 5 or 2 not in methods:
                return
            sock.sendall(b'\x05\x02')
            version, size = receive(sock, 2)
            username = receive(sock, size)
            password = receive(sock, receive(sock, 1)[0])
            if version != 1 or username != self.server.username or password != self.server.password:
                sock.sendall(b'\x01\x01')
                return
            sock.sendall(b'\x01\x00')
            version, command, _, kind = receive(sock, 4)
            if kind == 1:
                host = socket.inet_ntoa(receive(sock, 4))
            elif kind == 3:
                host = receive(sock, receive(sock, 1)[0]).decode()
            else:
                return
            port = int.from_bytes(receive(sock, 2), 'big')
            if version != 5 or command != 1 or (host, port) != self.server.destination:
                return
            with socket.create_connection(self.server.destination, timeout=10) as upstream:
                self.server.connections += 1
                sock.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01\x00\x00')
                while True:
                    readable, _, _ = select.select([sock, upstream], [], [], 5)
                    if not readable:
                        return
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        (upstream if source is sock else sock).sendall(data)
        except OSError:
            return
