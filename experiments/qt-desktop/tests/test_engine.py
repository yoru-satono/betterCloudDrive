import hashlib
import json
import threading
from pathlib import Path

import httpx
import pytest

from engine import DEFAULTS, Engine, PocError, digest, normalize_settings, safe_name


def task(target, content=b"abcdef"):
    return {"id": "fixture", "direction": "download", "apiBaseUrl": "http://127.0.0.1:8080/api/v1",
            "status": "paused", "rootNodeId": "fixture", "_account": "1", "nodes": [{
                "targetPath": str(target), "remoteFileId": 1, "bytesTotal": len(content),
                "bytesDone": 0, "progress": 0, "md5Hash": hashlib.md5(content).hexdigest()}]}


@pytest.mark.parametrize("status,range_header,body,start", [
    (206, "bytes 3-5/6", b"def", b"abc"),
    (200, None, b"abcdef", b"abc"),
    (416, "bytes */6", b"", b"abcdef"),
    (200, None, b"abcdef", b""),
])
def test_range_recovery(tmp_path, status, range_header, body, start):
    engine = Engine(tmp_path / "state")
    target = tmp_path / "result"
    target.with_name("result.part").write_bytes(start)
    item = task(target)
    engine.tasks = [item]
    seen = []
    def handler(request):
        seen.append(request.headers.get("range"))
        return httpx.Response(status, headers={"content-range": range_header} if range_header else {}, content=body)
    try:
        with httpx.Client(transport=httpx.MockTransport(handler)) as client:
            engine._download(item, client, threading.Event())
        assert target.read_bytes() == b"abcdef"
        assert item["status"] == "done"
        assert seen == ([f"bytes={len(start)}-"] if start else [None])
    finally:
        engine.close()


@pytest.mark.parametrize("status,headers,body,code", [
    (206, {"content-range": "bytes 1-5/6"}, b"bcdef", "INVALID_RANGE"),
    (416, {"content-range": "bytes */5"}, b"", "INVALID_RANGE"),
    (200, {}, b"short", "DOWNLOAD_INCOMPLETE"),
    (200, {}, b"XXXXXX", "DOWNLOAD_CHECKSUM_MISMATCH"),
    (401, {}, b"", "AUTH_EXPIRED"),
    (403, {}, b"", "PERMISSION_DENIED"),
])
def test_invalid_download_never_publishes_file(tmp_path, status, headers, body, code):
    engine = Engine(tmp_path / "state")
    item = task(tmp_path / "result")
    engine.tasks = [item]
    try:
        with httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status, headers=headers, content=body))) as client:
            with pytest.raises(PocError, match=code):
                engine._download(item, client, threading.Event())
        assert not (tmp_path / "result").exists()
    finally:
        engine.close()


def test_scope_cannot_redirect_credentials(tmp_path):
    engine = Engine(tmp_path)
    try:
        assert engine.checked_base(engine.api_base + "/teams/123")
        for suffix in ("/../other", "@evil.test", "/teams/1/../../other", "/teams/0", "?ticket=invalid"):
            with pytest.raises(PocError, match="API_SCOPE_DENIED"):
                engine.checked_base(engine.api_base + suffix)
    finally:
        engine.close()


def test_settings_secrets_never_persist(tmp_path):
    engine = Engine(tmp_path)
    try:
        engine.save_settings({**DEFAULTS, "proxyPassword": "synthetic-secret"})
        assert "synthetic-secret" not in json.dumps(engine._read("settings.json", {}))
        for values in ({"maxConcurrentUploads": 0}, {"maxConcurrentDownloads": 17},
                       {"uploadLimitBytesPerSec": -1}, {"proxyMode": "manual", "proxyUrl": "file:///tmp/a"}):
            with pytest.raises(PocError):
                normalize_settings(values)
    finally:
        engine.close()


def test_pause_restart_does_not_store_tokens(tmp_path):
    engine = Engine(tmp_path)
    item = task(tmp_path / "result")
    item["status"] = "transferring"
    engine.tasks = [item]
    engine.tokens[item["id"]] = "synthetic-token"
    engine._publish()
    engine.close()
    restored = Engine(tmp_path)
    try:
        assert "synthetic-token" not in json.dumps(restored._read("transfers.json", {}))
        assert restored.tasks[0]["status"] == "paused"
        assert restored.tokens == {}
    finally:
        restored.close()


def test_permission_error_is_terminal_without_retry(tmp_path):
    engine = Engine(tmp_path)
    item = task(tmp_path / "result")
    engine.tasks = [item]
    engine.tokens[item["id"]] = "synthetic-token"
    calls = []
    def handler(request):
        calls.append(request.url.path)
        if request.url.path.endswith("/auth/me"):
            return httpx.Response(200, json={"code": 200, "data": {"id": 1}})
        return httpx.Response(403)
    engine.client = lambda _: httpx.Client(transport=httpx.MockTransport(handler))
    engine._spawn(item)
    engine.futures[item["id"]].result(timeout=5)
    assert item["status"] == "error"
    assert item["nodes"][0]["error"] == "PERMISSION_DENIED"
    assert len(calls) == 2
    engine.close()


def test_concurrency_limit_and_cancellation(tmp_path):
    engine = Engine(tmp_path)
    engine.save_settings({**DEFAULTS, "maxConcurrentUploads": 1})
    gate, started = threading.Event(), threading.Event()
    engine.client = lambda _: httpx.Client(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json={"code": 200, "data": {"id": 1}})))
    def upload(item, client, stop):
        started.set()
        gate.wait(3)
        engine._check(stop)
        engine._update(item, "done")
    engine._upload = upload
    try:
        a = engine._new("upload", "a", engine.api_base, "synthetic", "1")
        assert started.wait(3)
        b = engine._new("upload", "b", engine.api_base, "synthetic", "1")
        engine.cancel(b["id"])
        assert engine.active["upload"] == 1
        gate.set()
        engine.futures[a["id"]].result(timeout=5)
        engine.futures[b["id"]].result(timeout=5)
        assert b["status"] == "canceled"
    finally:
        gate.set()
        engine.close()


@pytest.mark.parametrize("name", ["../a", "CON", "a/b", "a\\b", "...", "NUL.txt"])
def test_windows_safe_file_names(name):
    result = safe_name(name)
    assert result and "/" not in result and "\\" not in result
    assert result.split(".")[0].upper() not in ("CON", "NUL")
