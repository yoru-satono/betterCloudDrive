import hashlib
import json
import threading
from email.parser import BytesParser
from email.policy import default
from pathlib import Path

import httpx
import pytest

from engine import Engine, normalize_settings, safe_name
from network import DesktopError, ProxyAuth, api_url


def ok(value):
    return httpx.Response(200, json={"code": 200, "data": value})


class Backend:
    def __init__(self):
        self.content = b"abcdef"
        self.files = {}
        self.folders = []
        self.calls = []
        self.download_status = 200
        self.download_headers = {}
        self.download_body = None
        self.owner = 1

    def __call__(self, request):
        path = request.url.path
        self.calls.append((request.method, path, request.headers.get("range")))
        if path.endswith("/auth/me"):
            return ok({"id": self.owner})
        if path.endswith("/files/folder"):
            value = json.loads(request.content)
            self.folders.append(value)
            return ok({"id": 100 + len(self.folders)})
        if "/files/" in path:
            return ok({"id": 1, "fileType": "file", "fileSize": len(self.content),
                       "md5Hash": hashlib.md5(self.content).hexdigest()})
        if path.endswith("/upload/init"):
            value = json.loads(request.content)
            identity = str(len(self.files) + 1)
            self.files[identity] = {"meta": value, "chunks": {}}
            return ok({"mode": "MULTIPART", "sessionId": identity, "chunkSize": 3,
                       "totalChunks": (value["fileSize"] + 2) // 3})
        if "/upload/" in path:
            identity = path.split("/upload/")[1].split("/")[0]
            item = self.files[identity]
            if path.endswith("/status"):
                total = (item["meta"]["fileSize"] + 2) // 3
                return ok({"missingChunks": [i for i in range(total) if i not in item["chunks"]]})
            if path.endswith("/chunk"):
                message = BytesParser(policy=default).parsebytes(
                    ("Content-Type: " + request.headers["content-type"] + "\r\n\r\n").encode() + request.content)
                content = next(x.get_payload(decode=True) for x in message.iter_parts()
                               if x.get_param("name", header="content-disposition") == "file")
                assert hashlib.md5(content).hexdigest() == request.url.params["chunkMd5"]
                item["chunks"][int(request.url.params["chunkNumber"])] = content
                return ok(None)
            if path.endswith("/complete"):
                content = b"".join(v for _, v in sorted(item["chunks"].items()))
                assert hashlib.md5(content).hexdigest() == item["meta"]["md5Hash"]
                return ok({"fileId": int(identity)})
        if "/download/" in path:
            return httpx.Response(self.download_status, headers=self.download_headers,
                                  content=self.content if self.download_body is None else self.download_body)
        raise AssertionError(path)


@pytest.fixture
def system(tmp_path):
    api = Backend()
    engine = Engine(tmp_path / "state")
    engine.session.replace(engine.api_base, "1", "synthetic-token")
    engine.client = lambda owner=None, generation=None, stop=None: httpx.Client(
        transport=httpx.MockTransport(api),
        auth=ProxyAuth(engine.session, owner, generation, stop) if owner is not None else None)
    yield engine, api
    engine.close()


def wait(engine, task):
    engine.futures[task["id"]].result(timeout=5)
    return next(t for t in engine.queue()["tasks"] if t["id"] == task["id"])


def request(engine, **values):
    return {"apiBaseUrl": engine.api_base, "parentId": None, **values}


def test_directory_empty_unicode_batch_and_progress(system, tmp_path):
    engine, api = system
    events = []
    engine.emit = lambda name, value: events.append((name, value))
    source = tmp_path / "folder"
    (source / "empty").mkdir(parents=True)
    (source / "nested").mkdir()
    (source / "nested" / "a.txt").write_bytes(b"abcdef")
    (source / "zero.txt").touch()
    result = wait(engine, engine.upload_files([source], request(engine), folder=True))
    assert result["status"] == "done"
    assert len(api.folders) == 3
    assert len(api.files) == 2
    assert result["bytesDone"] == 6
    assert any(name == "desktop-upload:item-updated" and item["status"] == "done" for name, item in events)
    assert any(name == "desktop-upload:batch-completed" for name, _ in events)
    assert all("_manualPaused" not in n for n in result["nodes"])


def test_multiple_files_use_virtual_root_not_extra_remote_folder(system, tmp_path):
    engine, api = system
    a, b = tmp_path / "a", tmp_path / "b"
    a.write_bytes(b"abc")
    b.write_bytes(b"def")
    result = wait(engine, engine.upload_files([a, b], request(engine)))
    assert result["status"] == "done"
    assert not api.folders
    assert len(result["nodes"]) == 3
    assert all(f["meta"]["parentId"] is None for f in api.files.values())


@pytest.mark.parametrize("status,headers,body,part,passed", [
    (200, {}, b"abcdef", b"abc", True),
    (206, {"content-range": "bytes 3-5/6"}, b"def", b"abc", True),
    (416, {"content-range": "bytes */6"}, b"", b"abcdef", True),
    (206, {"content-range": "bytes 1-5/6"}, b"bcdef", b"abc", False),
    (416, {"content-range": "bytes */5"}, b"", b"abc", False),
    (200, {}, b"abc", b"", False),
    (200, {}, b"XXXXXX", b"", False),
    (200, {}, b"abcdefZ", b"", False),
    (403, {}, b"", b"", False),
])
def test_resume_validates_range_size_checksum_and_permission(system, tmp_path, status, headers, body, part, passed):
    engine, api = system
    engine._spawn = lambda _: None
    target = tmp_path / "download"
    task = engine.download(target, request(engine, fileId=1, fileName="download"))
    internal = engine.tasks[0]
    node = internal["nodes"][0]
    target.with_name(target.name + "." + node["id"] + ".part").write_bytes(part)
    api.download_status, api.download_headers, api.download_body = status, headers, body
    engine._run(internal, threading.Event(), engine.session.generation)
    assert (internal["status"] == "done") is passed
    assert target.exists() is passed
    if passed:
        assert target.read_bytes() == b"abcdef"
    if status == 403:
        assert internal["nodes"][0]["error"] == "PERMISSION_DENIED"
        assert len([p for _, p, _ in api.calls if "/download/" in p]) == 1


def test_new_queue_survives_restart_without_credentials(system, tmp_path):
    engine, _ = system
    engine._spawn = lambda _: None
    path = tmp_path / "source"
    path.write_bytes(b"abcdef")
    task = engine.upload_files([path], request(engine))
    stored = json.dumps(engine._read("queue", []))
    assert "synthetic-token" not in stored
    restored = Engine(engine.root)
    try:
        assert restored.queue() == {"tasks": []}
        restored.session.replace(restored.api_base, "1", "new-synthetic")
        assert restored.queue()["tasks"][0]["status"] == "paused"
        assert restored.queue()["tasks"][0]["id"] == task["id"]
    finally:
        restored.close()


def test_logout_revokes_session_and_hides_tasks(system, tmp_path):
    engine, _ = system
    engine._spawn = lambda _: None
    path = tmp_path / "a"
    path.write_bytes(b"abcdef")
    engine.upload_files([path], request(engine))
    generation = engine.session.generation
    engine.clear_session()
    assert not engine.session.token
    assert engine.queue() == {"tasks": []}
    with pytest.raises(DesktopError, match="SESSION_CHANGED"):
        engine.session.access("1", generation)


def test_server_and_account_switch_cannot_rebind_tasks(system, tmp_path):
    engine, api = system
    engine._spawn = lambda _: None
    path = tmp_path / "a"
    path.write_bytes(b"abc")
    item = engine.upload_files([path], request(engine))
    api.owner = 2
    engine.authenticate("synthetic-second", engine.api_base)
    assert engine.queue()["tasks"] == []
    with pytest.raises(DesktopError, match="TASK_NOT_FOUND"):
        engine.resume(item["id"])
    engine.configure("https://another.invalid/api/v1")
    assert not engine.session.token
    with pytest.raises(DesktopError, match="API_SCOPE_DENIED"):
        engine.checked_base("http://127.0.0.1:8080/api/v1")


def test_access_token_refresh_retries_once_without_persisting(system):
    engine, api = system
    original = api.__call__
    calls = []
    def handler(req):
        calls.append(req.headers["authorization"])
        if req.headers["authorization"] == "Bearer synthetic-token":
            return httpx.Response(401)
        return original(req)
    def emit(name, _):
        if name == "session:refresh-required":
            engine.session.replace(engine.api_base, "1", "synthetic-refreshed")
    engine.emit = emit
    with httpx.Client(transport=httpx.MockTransport(handler),
                     auth=ProxyAuth(engine.session, "1", engine.session.generation, None)) as client:
        assert client.get(engine.api_base + "/auth/me").status_code == 200
    assert calls == ["Bearer synthetic-token", "Bearer synthetic-refreshed"]
    assert "synthetic-refreshed" not in json.dumps(engine._read("queue", []))


def test_scope_and_secret_settings(system):
    engine, _ = system
    for suffix in ("/../other", "/teams/0", "/teams/1/../../other", "?ticket=x"):
        with pytest.raises(DesktopError):
            engine.checked_base(engine.api_base + suffix)
    engine.save_settings({"proxyPassword": "synthetic-password"})
    assert engine._read("settings", {})["proxyPassword"] == ""
    for values in ({"maxConcurrentUploads": 0}, {"maxConcurrentDownloads": 17},
                   {"downloadLimitBytesPerSec": -1}, {"proxyMode": "manual", "proxyUrl": "file:///a"}):
        with pytest.raises(DesktopError):
            normalize_settings(values)
    assert safe_name("../CON") == "_CON"


def test_pause_and_cancel_subtree_do_not_cancel_siblings(system, tmp_path):
    engine, _ = system
    engine._spawn = lambda _: None
    paths = [tmp_path / n for n in ("a", "b")]
    for p in paths:
        p.write_bytes(b"abcdef")
    task = engine.upload_files(paths, request(engine))
    a, b = task["nodes"][1:]
    engine.pause(a["id"])
    assert engine.tasks[0]["nodes"][1]["status"] == "paused"
    assert engine.tasks[0]["nodes"][2]["status"] == "pending"
    engine.cancel(b["id"])
    assert engine.tasks[0]["nodes"][2]["status"] == "canceled"
    engine._run(engine.tasks[0], threading.Event(), engine.session.generation)
    assert not any(n["status"] == "done" for n in engine.tasks[0]["nodes"])


def test_download_tree_renames_colliding_sanitized_paths(system, tmp_path):
    engine, _ = system
    tree = {"id": 9, "fileName": "root", "fileType": "folder", "children": [
        {"id": 1, "fileName": "a:b", "fileType": "file"},
        {"id": 1, "fileName": "a?b", "fileType": "file"},
    ]}
    task = wait(engine, engine.download(tmp_path / "output", request(engine), tree=tree))
    assert task["status"] == "done"
    assert sorted(p.name for p in (tmp_path / "output").iterdir()) == ["a_b", "a_b (1)"]


def test_no_legacy_json_import(tmp_path):
    (tmp_path / "settings.json").write_text('{"maxConcurrentUploads":1}')
    engine = Engine(tmp_path)
    try:
        assert engine.settings["maxConcurrentUploads"] == 3
    finally:
        engine.close()


def test_logout_during_authentication_cannot_restore_session(system):
    engine, api = system
    entered, release = threading.Event(), threading.Event()
    outcome = []
    def handler(req):
        entered.set()
        assert release.wait(3)
        return api(req)
    engine.client = lambda **_: httpx.Client(transport=httpx.MockTransport(handler))
    def authenticate():
        try:
            engine.authenticate("synthetic-login", engine.api_base)
        except DesktopError as error:
            outcome.append(str(error))
    worker = threading.Thread(target=authenticate)
    worker.start()
    assert entered.wait(2)
    engine.clear_session()
    release.set()
    worker.join(timeout=3)
    assert outcome == ["SESSION_CHANGED"]
    assert not engine.session.token


def test_concurrency_slot_cancellation_does_not_start_waiting_upload(system, tmp_path):
    engine, _ = system
    engine.save_settings({"maxConcurrentUploads": 1})
    entered, release = threading.Event(), threading.Event()
    executed = []
    def upload(task, node, client, child_stop, stop, generation):
        executed.append(task["id"])
        entered.set()
        assert release.wait(3)
        engine._check(task, node, stop, generation)
        engine._update(task, node, "done", bytesDone=node["bytesTotal"], progress=100)
    engine._upload = upload
    path = tmp_path / "a"
    path.write_bytes(b"abc")
    a = engine.upload_files([path], request(engine))
    assert entered.wait(2)
    b = engine.upload_files([path], request(engine))
    engine.cancel(b["id"])
    engine.futures[b["id"]].result(timeout=2)
    assert executed == [a["id"]]
    release.set()
    assert wait(engine, a)["status"] == "done"


def test_clear_finished_retains_paused_nodes_and_removes_failed_parts(system, tmp_path):
    engine, _ = system
    engine._spawn = lambda _: None
    target = tmp_path / "result"
    engine.download(target, request(engine, fileId=1, fileName="result"))
    node = engine.tasks[0]["nodes"][0]
    part = target.with_name(target.name + "." + node["id"] + ".part")
    part.write_bytes(b"partial")
    node["status"] = "paused"
    engine._aggregate(engine.tasks[0])
    engine.clear_finished()
    assert engine.tasks and part.exists()
    node["status"] = "error"
    engine._aggregate(engine.tasks[0])
    engine.clear_finished()
    assert not engine.tasks and not part.exists()


def test_reserved_auth_cannot_start_after_logout_or_server_switch(system):
    engine, api = system
    base = engine.api_base
    for revoke in (engine.clear_session, lambda: engine.configure('https://other.example/api/v1')):
        revision = engine.reserve_auth()
        revoke()
        before = len(api.calls)
        with pytest.raises(DesktopError, match='SESSION_CHANGED'):
            engine.authenticate('synthetic-late', base, revision)
        assert len(api.calls) == before
        assert not engine.session.token


def test_latest_auth_reservation_wins_and_failed_sync_revokes_identity(system):
    engine, api = system
    older, newest = engine.reserve_auth(), engine.reserve_auth()
    engine.authenticate('synthetic-newest', engine.api_base, newest)
    with pytest.raises(DesktopError, match='SESSION_CHANGED'):
        engine.authenticate('synthetic-older', engine.api_base, older)
    assert engine.session.token == 'synthetic-newest'
    engine.client = lambda **_: httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(401, json={'code': 401002})))
    with pytest.raises(DesktopError):
        engine.authenticate('synthetic-invalid', engine.api_base)
    assert not engine.session.token
