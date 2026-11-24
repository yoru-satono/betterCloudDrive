"""PoC transfer engine. No Qt dependency; credentials stay in memory."""
from __future__ import annotations

import copy
import hashlib
import json
import math
import os
import re
import sqlite3
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit

import httpx

DEFAULTS = dict(uploadLimitBytesPerSec=None, downloadLimitBytesPerSec=None,
                maxConcurrentUploads=3, maxConcurrentDownloads=3,
                defaultDownloadDir=None, proxyMode="system", proxyUrl="",
                proxyUsername="", proxyPassword="")
TERMINAL = {"done", "instant", "canceled", "error"}


class PocError(Exception):
    """Only stable, non-sensitive codes may cross the bridge or enter reports."""


class Interrupted(Exception):
    pass


def atomic_json(path: Path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    os.replace(tmp, path)


def digest(path: Path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "md5").hexdigest()


def safe_name(name: str):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    if not name or name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *(f"COM{x}" for x in range(1, 10)), *(f"LPT{x}" for x in range(1, 10))}:
        name = "_" + (name or "download")
    return name


def normalize_settings(values):
    result = {**DEFAULTS, **{k: v for k, v in values.items() if k in DEFAULTS}}
    for key in ("maxConcurrentUploads", "maxConcurrentDownloads"):
        if type(result[key]) is not int or not 1 <= result[key] <= 16:
            raise PocError("INVALID_CONCURRENCY")
    for key in ("uploadLimitBytesPerSec", "downloadLimitBytesPerSec"):
        if result[key] is not None and (type(result[key]) is not int or result[key] <= 0):
            raise PocError("INVALID_RATE_LIMIT")
    if result["proxyMode"] not in ("system", "manual", "disabled"):
        raise PocError("INVALID_PROXY")
    if result["proxyMode"] == "manual":
        proxy = urlsplit(result["proxyUrl"])
        if proxy.scheme not in ("http", "https", "socks5", "socks5h") or not proxy.hostname or proxy.username or proxy.password:
            raise PocError("INVALID_PROXY")
    return result


class Engine:
    def __init__(self, root: Path, api_base="http://127.0.0.1:8080/api/v1", emit=None):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.api_base = api_base.rstrip("/")
        parsed = urlsplit(self.api_base)
        if parsed.scheme not in ("http", "https") or parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise PocError("INVALID_API_BASE")
        self.emit = emit or (lambda *_: None)
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.executor = ThreadPoolExecutor(max_workers=16, thread_name_prefix="qt-transfer")
        self.futures, self.controls, self.tokens = {}, {}, {}
        self.active = {"upload": 0, "download": 0}
        self.closed = False
        self.db = sqlite3.connect(self.root / "state.sqlite3", check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.settings = normalize_settings(self._read("settings.json", {}))
        self.tasks = self._read("transfers.json", {"tasks": []})["tasks"]
        for task in self.tasks:
            if task["status"] not in TERMINAL:
                task["status"] = task["nodes"][0]["status"] = "paused"
        self.evidence = []

    def _read(self, name, default):
        record = self.db.execute("SELECT value FROM state WHERE name=?", (name,)).fetchone()
        if record:
            return json.loads(record[0])
        try:
            return json.loads((self.root / name).read_text(encoding="utf-8"))
        except FileNotFoundError:
            return default
        except (ValueError, KeyError, TypeError):
            raise PocError("INVALID_LOCAL_STATE") from None

    def _store(self, name, value):
        # Windows readers/AV can hold JSON files without FILE_SHARE_DELETE.
        # SQLite transactions avoid replacing a frequently observed pathname.
        self.db.execute("INSERT INTO state(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",
                        (name, json.dumps(value, ensure_ascii=False)))
        self.db.commit()

    def checked_base(self, base):
        base = base.rstrip("/")
        if base != self.api_base and not re.fullmatch(re.escape(self.api_base) + r"/teams/[1-9][0-9]*", base):
            raise PocError("API_SCOPE_DENIED")
        return base

    def client(self, token):
        with self.lock:
            s = dict(self.settings)
        proxy = None
        if s["proxyMode"] == "manual":
            auth = (s["proxyUsername"], s["proxyPassword"]) if s["proxyUsername"] else None
            proxy = httpx.Proxy(s["proxyUrl"], auth=auth)
        return httpx.Client(headers={"Authorization": f"Bearer {token}"},
                            timeout=httpx.Timeout(30, connect=10), follow_redirects=False,
                            trust_env=s["proxyMode"] == "system", proxy=proxy)

    @staticmethod
    def envelope(response):
        if response.status_code == 401:
            raise PocError("AUTH_EXPIRED")
        if response.status_code == 403:
            raise PocError("PERMISSION_DENIED")
        if response.status_code >= 400:
            raise PocError(f"HTTP_{response.status_code}")
        try:
            value = response.json()
        except ValueError:
            raise PocError("INVALID_API_RESPONSE") from None
        if value.get("code") in (401, 401001, 401002):
            raise PocError("AUTH_EXPIRED")
        if value.get("code") in (403, 403001, 403002, 403003):
            raise PocError("PERMISSION_DENIED")
        if value.get("code") != 200:
            raise PocError(f"API_{value.get('code', 'INVALID')}")
        return value.get("data")

    def account(self, client):
        value = self.envelope(client.get(self.api_base + "/auth/me"))
        return str(value.get("id", value.get("userId")))

    def save_settings(self, values):
        with self.lock:
            self.settings = normalize_settings(values)
            stored = {**self.settings, "proxyPassword": ""}
            self._store("settings.json", stored)
            self.condition.notify_all()
            return dict(self.settings)

    def queue(self):
        with self.lock:
            return {"tasks": copy.deepcopy([{k: v for k, v in t.items() if not k.startswith("_")} for t in self.tasks])}

    def _publish(self):
        with self.lock:
            self._store("transfers.json", {"tasks": self.tasks})
            snapshot = self.queue()
        self.emit("transfer:queue-updated", snapshot)

    def _find(self, node_id):
        with self.lock:
            for task in self.tasks:
                if task["id"] == node_id or task["rootNodeId"] == node_id:
                    return task
        raise PocError("TASK_NOT_FOUND")

    def _update(self, task, status=None, **node_values):
        with self.lock:
            node = task["nodes"][0]
            if status:
                node["status"] = task["status"] = status
            node.update(node_values)
            task.update(bytesDone=node["bytesDone"], bytesTotal=node["bytesTotal"],
                        progress=node["progress"], updatedAt=int(time.time() * 1000))
            self._publish()

    def _new(self, direction, name, base, token, owner, **values):
        identity, now = uuid.uuid4().hex, int(time.time() * 1000)
        node = dict(id=identity, taskId=identity, parentId=None, direction=direction,
                    kind="file", name=name, displayPath=name, path=[name], status="pending",
                    progress=0, bytesDone=0, bytesTotal=values.pop("size", 0), error=None,
                    localPath=None, targetPath=None, remoteFileId=None, remoteParentId=None,
                    uploadId=None, md5Hash=None, totalChunks=0, completedChunks=0, chunkSize=0)
        node.update(values)
        task = dict(id=identity, direction=direction, rootNodeId=identity, name=name,
                    status="pending", progress=0, bytesDone=0, bytesTotal=node["bytesTotal"],
                    createdAt=now, updatedAt=now, apiBaseUrl=base, baseParentId=node["remoteParentId"],
                    nodes=[node], _account=owner)
        with self.lock:
            self.tasks.append(task)
            self.tokens[identity] = token
            self._publish()
            self._spawn(task)
        return task

    def upload(self, source: Path, request):
        if source.is_symlink():
            raise PocError("INVALID_SOURCE")
        source = source.resolve(strict=True)
        if not source.is_file():
            raise PocError("INVALID_SOURCE")
        base, token = self.checked_base(request["apiBaseUrl"]), request["token"]
        with self.client(token) as client:
            owner = self.account(client)
        return self._new("upload", source.name, base, token, owner, localPath=str(source),
                         size=source.stat().st_size, remoteParentId=request.get("parentId"))

    def download(self, target: Path, request):
        target = target.resolve()
        base, token = self.checked_base(request["apiBaseUrl"]), request.get("token", "")
        with self.client(token) as client:
            owner = self.account(client)
            metadata_base = base + ("/files/" if "/teams/" in base else "/files/")
            metadata = self.envelope(client.get(metadata_base + str(int(request["fileId"]))))
        return self._new("download", request["fileName"], base, token, owner,
                         targetPath=str(target), remoteFileId=int(request["fileId"]),
                         size=metadata["fileSize"], md5Hash=metadata.get("md5Hash"))

    def _spawn(self, task):
        if self.closed:
            raise PocError("ENGINE_CLOSED")
        previous = self.futures.get(task["id"])
        if previous and not previous.done():
            raise PocError("TASK_BUSY")
        stop = threading.Event()
        self.controls[task["id"]] = stop
        self.futures[task["id"]] = self.executor.submit(self._run, task, stop)

    def pause(self, node_id):
        task = self._find(node_id)
        if task["status"] not in TERMINAL:
            self.controls.get(task["id"], threading.Event()).set()
            self._update(task, "paused")
        return self.queue()

    def resume(self, node_id, token, api_base):
        task = self._find(node_id)
        base = self.checked_base(api_base)
        if task["apiBaseUrl"] != base and not task["apiBaseUrl"].startswith(base + "/teams/"):
            raise PocError("API_SCOPE_DENIED")
        with self.client(token) as client:
            if "_account" not in task:
                raise PocError("LEGACY_ACCOUNT_REBIND_REQUIRED")
            if self.account(client) != task["_account"]:
                raise PocError("ACCOUNT_MISMATCH")
        with self.lock:
            if task["status"] in ("done", "instant", "canceled"):
                return self.queue()
            previous = self.futures.get(task["id"])
            if previous and not previous.done():
                raise PocError("TASK_BUSY")
            self.tokens[task["id"]] = token
            self._update(task, "pending", error=None)
            self._spawn(task)
        return self.queue()

    def cancel(self, node_id):
        task = self._find(node_id)
        self.controls.get(task["id"], threading.Event()).set()
        self._update(task, "canceled")
        return self.queue()

    def clear_finished(self):
        with self.lock:
            # Keep jobs whose workers have not yet acknowledged cancellation.
            self.tasks = [t for t in self.tasks if t["status"] not in TERMINAL or
                          (t["id"] in self.futures and not self.futures[t["id"]].done())]
            retained = {t["id"] for t in self.tasks}
            self.tokens = {k: v for k, v in self.tokens.items() if k in retained}
            self._publish()
        return self.queue()

    def _check(self, stop):
        if stop.is_set():
            raise Interrupted()

    def _throttle(self, direction, count, started, stop):
        limit = self.settings[direction + "LimitBytesPerSec"]
        if limit:
            delay = count / limit - (time.monotonic() - started)
            if delay > 0 and stop.wait(delay):
                raise Interrupted()

    def _run(self, task, stop):
        direction = task["direction"]
        acquired = False
        try:
            with self.condition:
                while self.active[direction] >= self.settings["maxConcurrent" + direction.capitalize() + "s"]:
                    self._check(stop)
                    self.condition.wait(0.1)
                self._check(stop)
                self.active[direction] += 1
                acquired = True
            with self.client(self.tokens[task["id"]]) as client:
                if self.account(client) != task["_account"]:
                    raise PocError("ACCOUNT_MISMATCH")
                if direction == "upload":
                    self._upload(task, client, stop)
                else:
                    self._download(task, client, stop)
        except Interrupted:
            pass
        except Exception as error:
            if not stop.is_set():
                import traceback
                self.evidence.append({"kind": "failure", "exception": type(error).__name__,
                    "frames": [{"function": f.name, "line": f.lineno} for f in traceback.extract_tb(error.__traceback__)]})
                code = str(error) if isinstance(error, PocError) else "TRANSFER_IO_FAILED_" + type(error).__name__
                self._update(task, "error", error=code)
                self.emit("transfer:error", {"message": code})
        finally:
            if acquired:
                with self.condition:
                    self.active[direction] -= 1
                    self.condition.notify_all()

    def _upload(self, task, client, stop):
        node, base = task["nodes"][0], task["apiBaseUrl"]
        path = Path(node["localPath"])
        self._update(task, "hashing")
        checksum = digest(path)
        self._check(stop)
        if node["md5Hash"] and checksum != node["md5Hash"]:
            raise PocError("SOURCE_CHANGED")
        self._update(task, md5Hash=checksum)
        session = node["uploadId"]
        if session:
            state = self.envelope(client.get(f"{base}/upload/{session}/status"))
        else:
            result = self.envelope(client.post(base + "/upload/init", json={
                "parentId": node["remoteParentId"], "fileName": node["name"],
                "fileSize": path.stat().st_size, "md5Hash": checksum}))
            if result.get("mode") == "INSTANT" and result.get("fileId"):
                self._update(task, "instant", remoteFileId=result["fileId"], progress=100, bytesDone=node["bytesTotal"])
                return
            if (result.get("mode") != "MULTIPART" or not result.get("sessionId")
                    or result.get("chunkSize", 0) <= 0 or result.get("totalChunks", -1) < 0
                    or result["totalChunks"] != math.ceil(path.stat().st_size / result["chunkSize"])):
                raise PocError("INVALID_UPLOAD_INIT")
            session = result["sessionId"]
            self._update(task, uploadId=session, chunkSize=result["chunkSize"], totalChunks=result["totalChunks"])
            state = self.envelope(client.get(f"{base}/upload/{session}/status"))
        missing = state.get("missingChunks")
        if not isinstance(missing, list):
            raise PocError("INVALID_UPLOAD_STATUS")
        self.evidence.append({"kind": "upload-missing", "missingCount": len(missing), "totalChunks": node["totalChunks"]})
        started, sent = time.monotonic(), 0
        with path.open("rb") as source:
            for number in missing:
                self._check(stop)
                source.seek(number * node["chunkSize"])
                content = source.read(node["chunkSize"])
                self.envelope(client.post(f"{base}/upload/{session}/chunk", params={
                    "chunkNumber": number, "chunkMd5": hashlib.md5(content).hexdigest()},
                    files={"file": ("part", content, "application/octet-stream")}))
                sent += len(content)
                completed = node["totalChunks"] - len(missing) + missing.index(number) + 1
                done = min(node["bytesTotal"], completed * node["chunkSize"])
                self._update(task, "transferring", completedChunks=completed, bytesDone=done,
                             progress=completed / node["totalChunks"] * 100)
                self._throttle("upload", sent, started, stop)
        self._check(stop)
        result = self.envelope(client.post(f"{base}/upload/{session}/complete"))
        self._update(task, "done", remoteFileId=result["fileId"], bytesDone=node["bytesTotal"], progress=100)
        self.emit("desktop-upload:batch-completed", None)

    def _download(self, task, client, stop):
        node = task["nodes"][0]
        target = Path(node["targetPath"])
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_name(target.name + ".part")
        start = part.stat().st_size if part.exists() else 0
        headers = {"Range": f"bytes={start}-"} if start else {}
        with client.stream("GET", f"{task['apiBaseUrl']}/download/{node['remoteFileId']}", headers=headers) as response:
            self.evidence.append({"kind": "download-range", "start": start, "status": response.status_code})
            if "application/json" in response.headers.get("content-type", "") and not response.headers.get("content-disposition"):
                response.read()
                self.envelope(response)
                raise PocError("INVALID_DOWNLOAD_RESPONSE")
            if response.status_code == 416:
                if response.headers.get("content-range") != f"bytes */{start}" or start != node["bytesTotal"]:
                    raise PocError("INVALID_RANGE")
            else:
                if response.status_code not in (200, 206):
                    response.read()
                    self.envelope(response)
                    raise PocError("INVALID_DOWNLOAD_STATUS")
                if response.status_code == 206:
                    match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("content-range", ""))
                    if not match or int(match[1]) != start or int(match[3]) != node["bytesTotal"] or int(match[2]) < start:
                        raise PocError("INVALID_RANGE")
                elif start:
                    start = 0  # Range ignored: replace, never append a complete response.
                self._update(task, "transferring", bytesDone=start)
                started, received = time.monotonic(), 0
                with part.open("ab" if start else "wb") as output:
                    for content in response.iter_bytes(256 * 1024):
                        self._check(stop)
                        output.write(content)
                        received += len(content)
                        done = start + received
                        self._update(task, bytesDone=done, progress=done / max(1, node["bytesTotal"]) * 100)
                        self._throttle("download", received, started, stop)
        self._check(stop)
        if part.stat().st_size != node["bytesTotal"]:
            raise PocError("DOWNLOAD_INCOMPLETE")
        if node["md5Hash"] and digest(part) != node["md5Hash"]:
            raise PocError("DOWNLOAD_CHECKSUM_MISMATCH")
        os.replace(part, target)
        self._update(task, "done", bytesDone=node["bytesTotal"], progress=100)

    def close(self):
        self.closed = True
        for task in list(self.tasks):
            if task["status"] not in TERMINAL:
                self.pause(task["id"])
        self.executor.shutdown(wait=True, cancel_futures=True)
        self.tokens.clear()
        self.db.close()
