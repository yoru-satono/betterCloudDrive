import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from types import SimpleNamespace

import httpx
import pytest

from test_engine import system
from test_host import qt
from media import MediaGateway


@pytest.mark.parametrize('value,status,body,span', [
    (None, 200, b'abcdef', None), ('bytes=1-3', 206, b'bcd', 'bytes 1-3/6'),
    ('bytes=3-', 206, b'def', 'bytes 3-5/6'), ('bytes=-2', 206, b'ef', 'bytes 4-5/6'),
    ('bytes=0-99', 206, b'abcdef', 'bytes 0-5/6'), ('bytes=6-', 416, b'', 'bytes */6'),
    ('bytes=3-1', 416, b'', 'bytes */6'), ('bytes=-0', 416, b'', 'bytes */6'),
    ('bytes=0-1,3-4', 416, b'', 'bytes */6'),
])
def test_gateway_range_and_authorized_head(system, value, status, body, span):
    engine, api = system
    gateway = MediaGateway(engine)
    try:
        _, url = gateway.register(1)
        headers = {'Range': value} if value else {}
        head = httpx.head(url, headers=headers, trust_env=False)
        assert head.status_code == status and head.content == b''
        assert not any('/download/' in path for _, path, _ in api.calls)
        response = httpx.get(url, headers=headers, trust_env=False)
        assert response.status_code == status and response.content == body
        assert response.headers.get('content-range') == span
        assert head.headers.get('content-range') == span
        assert int(head.headers['content-length']) == len(body)
    finally:
        gateway.close()


@pytest.mark.parametrize('headers,body', [
    ({'content-range': 'bytes 0-2/6'}, b'abc'),
    ({'content-range': 'bytes 3-5/7'}, b'def'),
    ({'content-range': 'bytes 3-5/6', 'content-length': '4'}, b'def!'),
])
def test_gateway_rejects_malformed_upstream_range_without_publishing_bytes(system, headers, body):
    engine, api = system
    gateway = MediaGateway(engine)
    failures = []
    gateway.failed = lambda _, code: failures.append(code)
    try:
        _, url = gateway.register(1)
        api.download_status, api.download_headers, api.download_body = 206, headers, body
        response = httpx.get(url, headers={'Range': 'bytes=3-'}, trust_env=False)
        assert response.status_code == 502 and response.content == b''
        assert failures == ['MEDIA_INVALID_RANGE']
    finally:
        gateway.close()


def test_file_version_change_rejected_before_head_or_download(system):
    engine, api = system
    gateway = MediaGateway(engine)
    try:
        _, url = gateway.register(1)
        api.content = b'new version'
        assert httpx.head(url, trust_env=False).status_code == 502
        assert httpx.get(url, trust_env=False).status_code == 502
        assert not any('/download/' in path for _, path, _ in api.calls)
    finally:
        gateway.close()


def test_large_stream_uses_bounded_completed_upstream_ranges(system):
    import hashlib
    from media import byte_range
    engine, api = system
    content = b'0123456789abcdef' * (9 * 1024 * 1024 // 16)
    api.content = content
    ranges = []
    def backend(request):
        if '/download/' not in request.url.path: return api(request)
        selected = byte_range(request.headers['range'], len(content)); ranges.append(selected)
        first, last = selected
        return httpx.Response(206, content=content[first:last+1], headers={'Content-Range': f'bytes {first}-{last}/{len(content)}'})
    engine.client = lambda *_, **__: httpx.Client(transport=httpx.MockTransport(backend))
    gateway = MediaGateway(engine)
    try:
        _, url = gateway.register(1)
        response = httpx.get(url, trust_env=False)
        assert response.status_code == 200 and hashlib.md5(response.content).digest() == hashlib.md5(content).digest()
        chunk = 4 * 1024 * 1024
        assert ranges == [(0, chunk-1), (chunk, chunk*2-1), (chunk*2, len(content)-1)]
        assert not any(gateway.responses.values())
    finally:
        gateway.close()


def test_version_change_during_segment_fetch_publishes_no_bytes(system):
    engine, api = system
    def backend(request):
        result = api(request)
        if '/download/' in request.url.path: api.content = b'edited'
        return result
    engine.client = lambda *_, **__: httpx.Client(transport=httpx.MockTransport(backend))
    gateway = MediaGateway(engine)
    try:
        _, url = gateway.register(1)
        response = httpx.get(url, trust_env=False)
        assert response.status_code == 502 and response.content == b''
        assert gateway.last_failure['code'] == 'MEDIA_CHANGED'
    finally:
        gateway.close()


@pytest.mark.parametrize('during', ['headers', 'body'])
def test_revoke_interrupts_real_stalled_upstream_within_two_seconds(system, during):
    engine, api = system
    started, disconnected = threading.Event(), threading.Event()
    class Upstream(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            if '/files/' in self.path:
                content = json.dumps({'code': 200, 'data': {'fileType': 'file', 'fileSize': 6, 'md5Hash': 'fixture'}}).encode()
                self.send_response(200); self.send_header('Content-Length', str(len(content))); self.end_headers(); self.wfile.write(content)
                return
            if during == 'body':
                self.send_response(200); self.send_header('Content-Length', '6'); self.end_headers(); self.wfile.write(b'a'); self.wfile.flush()
            started.set()
            self.connection.settimeout(5)
            try:
                if self.connection.recv(1) == b'': disconnected.set()
            except (ConnectionResetError, ConnectionAbortedError): disconnected.set()
            except OSError: pass
    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
    thread = threading.Thread(target=upstream.serve_forever, daemon=True); thread.start()
    # Use actual TCP for this case; a MockTransport cannot prove socket revocation.
    engine.client = lambda *_, **__: httpx.Client(timeout=8, trust_env=False)
    engine.session.replace(f'http://127.0.0.1:{upstream.server_port}/api/v1', '1', 'synthetic-token')
    gateway = MediaGateway(engine)
    done = threading.Event()
    def reader(url):
        try: httpx.get(url, timeout=8, trust_env=False)
        except httpx.HTTPError: pass
        finally: done.set()
    try:
        identity, url = gateway.register(1)
        consumer = threading.Thread(target=reader, args=(url,), daemon=True); consumer.start()
        assert started.wait(3)
        start = time.monotonic(); gateway.revoke(identity)
        assert done.wait(1.8) and disconnected.wait(max(0, 1.9 - (time.monotonic() - start)))
        assert time.monotonic() - start < 2
        deadline = time.monotonic() + 1
        while gateway.active_requests and time.monotonic() < deadline: time.sleep(.01)
        assert gateway.active_requests == 0 and not gateway.entries
    finally:
        gateway.close(); upstream.shutdown(); upstream.server_close(); thread.join()


def test_latest_open_reuses_one_window_and_old_responses_cannot_replace_it(system, qt, monkeypatch):
    from PySide6.QtCore import QObject, Signal, QCoreApplication, QEvent
    from PySide6.QtWidgets import QMainWindow
    import app
    class Player(QObject):
        closed = Signal()
        retry_requested = Signal()
        def __init__(self, gateway, identity, url, name, parent, locale, opened):
            super().__init__(parent); self.gateway = gateway; self.identity = identity; self.names = [name]
        def load(self, identity, url, name, *_): self.identity = identity; self.names.append(name)
        def loading(self, *_): self.gateway.revoke(self.identity); self.identity = ''
        def show(self): pass
        def failure(self, *_): pass
        def close(self): self.gateway.revoke(self.identity); self.closed.emit()
    monkeypatch.setattr(app, 'PlayerWindow', Player)
    engine, _ = system; window = QMainWindow(); bridge = app.Bridge(window, engine, SimpleNamespace(debug_port=None))
    queued, responses = [], []
    monkeypatch.setattr(bridge.workers, 'submit', queued.append)
    bridge.response.connect(lambda value: responses.append(json.loads(value)))
    def open_video(name): bridge.dispatch(json.dumps({'id': name, 'command': 'media:open', 'args': {'fileId': 1, 'fileName': name}}))
    try:
        methods = bridge.metaObject().methodCount()
        open_video('old'); open_video('new'); queued.pop()(); queued.pop()()
        assert bridge.metaObject().methodCount() == methods  # WebChannel metadata must stay stable after registration.
        assert len(bridge.players) == 1 and bridge.players[0].names == ['new']
        assert responses[-1]['error'] == 'MEDIA_SUPERSEDED'
        player = bridge.players[0]; open_video('next'); assert not bridge.gateway.entries
        queued.pop()(); assert bridge.players == [player] and player.names == ['new', 'next']
        open_video('late'); bridge.close_players(); queued.pop()()
        assert not bridge.players and not bridge.gateway.entries
    finally:
        queued.clear(); bridge.close_players(); bridge.workers.shutdown(); bridge.gateway.close(); window.close(); window.deleteLater()
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete); qt.processEvents()


def test_playback_acceptance_timer_keeps_registered_webchannel_metadata_stable(system, qt):
    from PySide6.QtCore import QTimer, QCoreApplication, QEvent
    from PySide6.QtWidgets import QMainWindow, QWidget
    from m3_player_worker import InstrumentedBridge
    class View(QWidget):
        from PySide6.QtCore import Signal
        loadFinished = Signal(bool)
    engine, _ = system
    window = QMainWindow(); window.setCentralWidget(View())
    bridge = InstrumentedBridge(window, engine, SimpleNamespace(debug_port=None))
    try:
        methods = bridge.metaObject().methodCount()
        # Scheduling a bound, undeclared Qt slot after registration changes the
        # QObject metadata and breaks subsequent QWebChannel confirmations.
        QTimer.singleShot(500, bridge.next_sample)
        assert bridge.metaObject().methodCount() == methods
    finally:
        bridge.clock.stop(); bridge.sampler.stop()
        bridge.workers.shutdown(); bridge.gateway.close(); window.close(); window.deleteLater()
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete); qt.processEvents()
