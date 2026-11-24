import json
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import httpx
import pytest

from app import StaticServer
from engine import DEFAULTS, Engine


def test_occupied_loopback_port_is_rejected(tmp_path):
    server = StaticServer(tmp_path, 0)
    try:
        with pytest.raises(OSError):
            StaticServer(tmp_path, server.server_port)
    finally:
        server.server_close()


def test_existing_unmarked_profile_is_not_touched(tmp_path):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("fixture")
    profile = tmp_path / "existing"
    profile.mkdir()
    original = profile / "private.txt"
    original.write_text("unchanged")
    result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "app.py"),
                             "--profile", str(profile), "--web-root", str(web)], capture_output=True, text=True)
    assert result.returncode == 2
    assert "Profile directory is not marked" in result.stderr
    assert original.read_text() == "unchanged"
    assert not (profile / ".bcd-qt-poc").exists()


def test_static_host_refuses_traversal_and_foreign_hosts(tmp_path):
    web = tmp_path / "web"
    web.mkdir()
    (web / "index.html").write_text("fixture")
    (tmp_path / "private.txt").write_text("private")
    server = StaticServer(web, 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        base = f"http://127.0.0.1:{server.server_port}"
        with httpx.Client(trust_env=False) as client:
            assert client.get(base).text == "fixture"
            assert client.get(base + "/%2e%2e/private.txt").status_code == 403
            assert client.get(base, headers={"Host": "external.invalid"}).status_code == 403
            assert client.get(base + "/native-command").status_code == 404
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def test_manual_proxy_is_used_and_password_is_not_saved(tmp_path):
    requested = []
    class Proxy(BaseHTTPRequestHandler):
        def do_GET(self):
            requested.append(self.path)
            content = json.dumps({"code": 200, "data": {"id": 1}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        def log_message(self, *_):
            pass
    proxy = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    engine = Engine(tmp_path)
    try:
        engine.save_settings({**DEFAULTS, "proxyMode": "manual", "proxyUrl": f"http://127.0.0.1:{proxy.server_port}",
                              "proxyUsername": "synthetic", "proxyPassword": "synthetic-password"})
        with engine.client("synthetic-token") as client:
            assert engine.account(client) == "1"
        assert requested == [engine.api_base + "/auth/me"]
        assert engine._read("settings.json", {})["proxyPassword"] == ""
    finally:
        engine.close()
        proxy.shutdown()
        proxy.server_close()
        thread.join()


def test_rate_limit_is_interruptible_and_disabled_is_immediate(tmp_path):
    engine = Engine(tmp_path)
    try:
        engine.save_settings({**DEFAULTS, "downloadLimitBytesPerSec": 1000})
        started = time.monotonic()
        engine._throttle("download", 60, started, threading.Event())
        assert time.monotonic() - started >= 0.05
        engine.save_settings(DEFAULTS)
        engine._throttle("download", 2**60, time.monotonic(), threading.Event())
    finally:
        engine.close()


def test_empty_upload_uses_zero_chunks_and_completes(tmp_path):
    source = tmp_path / "empty.txt"
    source.touch()
    engine = Engine(tmp_path / "state")
    called = []
    def response(request):
        called.append(request.url.path)
        if request.url.path.endswith("/init"):
            data = {"mode": "MULTIPART", "sessionId": "fixture", "chunkSize": 5242880, "totalChunks": 0}
        elif request.url.path.endswith("/status"):
            data = {"missingChunks": []}
        else:
            data = {"fileId": 1}
        return httpx.Response(200, json={"code": 200, "data": data})
    task = {"id": "fixture", "apiBaseUrl": engine.api_base, "status": "pending", "nodes": [{
        "localPath": str(source), "md5Hash": None, "uploadId": None, "name": "empty.txt",
        "remoteParentId": None, "bytesTotal": 0, "bytesDone": 0, "progress": 0}]}
    engine.tasks = [task]
    try:
        with httpx.Client(transport=httpx.MockTransport(response)) as client:
            engine._upload(task, client, threading.Event())
        assert task["status"] == "done"
        assert not any(path.endswith("/chunk") for path in called)
    finally:
        engine.close()
