"""Native transfer queue. No legacy imports; the server remains authoritative."""
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
from concurrent.futures import ThreadPoolExecutor, Future
from pathlib import Path

import httpx

from network import DesktopError, Interrupted, ProxyAuth, Session, api_url, client_options, envelope
from transport import LiveClient, retry_request, retry_delay, TRANSIENT, RETRY_STATUS

DEFAULTS = dict(uploadLimitBytesPerSec=None, downloadLimitBytesPerSec=None,
                maxConcurrentUploads=3, maxConcurrentDownloads=3,
                defaultDownloadDir=None, proxyMode="system", proxyUrl="",
                proxyUsername="", proxyPassword="")
FINISHED = {"done", "instant", "canceled"}


def safe_name(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", str(name)).strip(" .")
    if not name or name.split(".")[0].upper() in {
        "CON", "PRN", "AUX", "NUL", *(f"COM{x}" for x in range(1, 10)), *(f"LPT{x}" for x in range(1, 10))
    }:
        name = "_" + (name or "download")
    return name


def normalize_settings(values):
    s = {**DEFAULTS, **{k: v for k, v in values.items() if k in DEFAULTS}}
    if any(not isinstance(s[key], str) for key in ("proxyUrl", "proxyUsername", "proxyPassword")):
        raise DesktopError("INVALID_PROXY")
    for key in ("maxConcurrentUploads", "maxConcurrentDownloads"):
        if type(s[key]) is not int or not 1 <= s[key] <= 16:
            raise DesktopError("INVALID_CONCURRENCY")
    for key in ("uploadLimitBytesPerSec", "downloadLimitBytesPerSec"):
        if s[key] is not None and (type(s[key]) is not int or s[key] <= 0):
            raise DesktopError("INVALID_RATE_LIMIT")
    if s["proxyMode"] not in ("system", "manual", "disabled"):
        raise DesktopError("INVALID_PROXY")
    if s["proxyMode"] == "manual":
        from urllib.parse import urlsplit
        p = urlsplit(s["proxyUrl"])
        try:
            p.port
        except ValueError:
            raise DesktopError("INVALID_PROXY") from None
        if p.scheme not in ("http", "https", "socks5", "socks5h") or not p.hostname or p.username or p.password or p.query or p.fragment or p.path not in ("", "/"):
            raise DesktopError("INVALID_PROXY")
    if s["defaultDownloadDir"] is not None and not isinstance(s["defaultDownloadDir"], str):
        raise DesktopError("INVALID_DOWNLOAD_DIRECTORY")
    if s["defaultDownloadDir"] and not Path(s["defaultDownloadDir"]).is_absolute():
        raise DesktopError("INVALID_DOWNLOAD_DIRECTORY")
    return s


def public(value):
    if isinstance(value, dict):
        return {k: public(v) for k, v in value.items() if not k.startswith("_")}
    if isinstance(value, list):
        return [public(v) for v in value]
    return value


class Engine:
    def __init__(self, root, api_base="http://127.0.0.1:8080/api/v1", emit=None):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.emit = emit or (lambda *_: None)
        self.session = Session(api_base, lambda *args: self.emit(*args))
        self.executor = ThreadPoolExecutor(max_workers=34, thread_name_prefix="desktop-transfer")
        self.cleanup_executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix="desktop-cleanup")
        self.futures, self.controls, self.node_controls = {}, {}, {}
        self.active = {"upload": 0, "download": 0}
        self.rate_tickets = {"upload": [], "download": []}
        self.rate_last = {"upload": time.monotonic(), "download": time.monotonic()}
        self.closed = False
        self.state_failed = False
        self.auth_revision = 0
        self.inflight = {}
        self.task_generations = {}
        self.directory_active = 0
        self.schedule_cursor = 0
        self.last_publish = 0
        self.db = sqlite3.connect(self.root / "desktop.sqlite3", check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        stored_settings = self._read("settings", {})
        self.settings = normalize_settings({**stored_settings, "proxyUsername": "", "proxyPassword": ""})
        if stored_settings.get("proxyUsername") or stored_settings.get("proxyPassword"):
            self._store("settings", self.settings)
        self.tasks = self._read("queue", [])
        for task in self.tasks:
            for node in task["nodes"]:
                if node["kind"] == "folder":
                    node.setdefault("_directoryReady", bool(node.get("_virtual") or node.get("remoteFileId") is not None or (task["direction"] == "download" and Path(node["targetPath"]).is_dir())))
                    if task["direction"] == "download" and node.get("_creatingDirectory"):
                        path = Path(node["targetPath"])
                        if path.is_dir() and not path.is_symlink() and not path.is_junction():
                            node["_directoryReady"], node["_creatingDirectory"] = True, False
                if node["status"] not in FINISHED:
                    node["status"] = "paused"
                    node["_manualPaused"] = True
            self._aggregate(task)
        self.scheduler = threading.Thread(target=self._schedule_loop, daemon=True, name="desktop-scheduler")
        self.scheduler.start()

    @property
    def api_base(self):
        return self.session.base

    def _read(self, name, default):
        row = self.db.execute("SELECT value FROM state WHERE name=?", (name,)).fetchone()
        try:
            return json.loads(row[0]) if row else default
        except (ValueError, TypeError):
            raise DesktopError("INVALID_LOCAL_STATE") from None

    def _store(self, name, value):
        self.db.execute("INSERT INTO state VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value",
                        (name, json.dumps(value, ensure_ascii=False)))
        self.db.commit()

    def client(self, owner=None, generation=None, stop=None):
        with self.lock:
            settings = dict(self.settings)
        auth = ProxyAuth(self.session, owner, generation, stop) if owner is not None else None
        return httpx.Client(auth=auth, **client_options(settings, self.api_base))

    def configure(self, base):
        base = api_url(base)
        with self.lock:
            if base != self.api_base:
                self.clear_session()
                self.session.replace(base)
            return {"apiBaseUrl": self.api_base}

    def reserve_auth(self):
        # Reserve on command receipt, before a worker can be delayed by HTTP.
        with self.lock:
            self.auth_revision += 1
            return self.auth_revision

    def authenticate(self, token, base, revision=None):
        revision = self.reserve_auth() if revision is None else revision
        base = api_url(base)
        with self.lock:
            if revision != self.auth_revision:
                raise DesktopError("SESSION_CHANGED")
            if base != self.api_base:
                raise DesktopError("API_SCOPE_DENIED")
            if not token:
                return self.clear_session()
            generation = self.session.generation
        try:
            with self.client() as client:
                user = envelope(client.get(base + "/auth/me", headers={"Authorization": f"Bearer {token}"}))
            owner = str(user.get("id", user.get("userId", "")))
            if not owner:
                raise DesktopError("INVALID_ACCOUNT")
        except Exception:
            with self.lock:
                if revision == self.auth_revision:
                    self.clear_session()
            raise
        with self.lock:
            if revision != self.auth_revision or base != self.api_base or generation != self.session.generation:
                raise DesktopError("SESSION_CHANGED")
            if self.session.owner != owner:
                self._stop_all()
            self.session.replace(base, owner, token)
            self._publish()
        return {"authenticated": True}

    def clear_session(self):
        with self.lock:
            self.auth_revision += 1
            self._stop_all()
            self.session.replace(self.api_base, invalidate=True)
            self._publish()
        return {"authenticated": False}

    def _stop_all(self):
        for control in self.node_controls.values():
            control.set()
        for task in self.tasks:
            self.controls.get(task["id"], threading.Event()).set()
            for node in task["nodes"]:
                if node["status"] not in FINISHED:
                    node["status"] = "paused"
                    node["_manualPaused"] = True
            self._aggregate(task)
        with self.session.condition:
            self.session.condition.notify_all()
        self.condition.notify_all()
        self.emit("session:ended", None)

    def checked_base(self, base):
        base = str(base).rstrip("/")
        if base != self.api_base and not re.fullmatch(re.escape(self.api_base) + r"/teams/[1-9][0-9]*", base):
            raise DesktopError("API_SCOPE_DENIED")
        if not self.session.owner:
            raise DesktopError("AUTH_REQUIRED")
        return base

    def context(self):
        with self.lock:
            return (self.session.owner, self.api_base, self.session.generation)

    def require_context(self, context):
        if context != self.context():
            raise DesktopError("SESSION_CHANGED")

    def save_settings(self, values):
        with self.lock:
            updated = normalize_settings(values)
            try:
                self._store("settings", {**updated, "proxyUsername": "", "proxyPassword": ""})
            except sqlite3.Error:
                raise DesktopError("SETTINGS_SAVE_FAILED") from None
            for direction in ("upload", "download"):
                self._rate_pump(direction)
            self.settings = updated
            self.condition.notify_all()
            return dict(self.settings)

    def queue(self):
        with self.lock:
            return {"tasks": public([t for t in self.tasks
                                    if t["_account"] == self.session.owner and
                                    t["_server"] == self.api_base])}

    def _publish(self, force=True):
        now = time.monotonic()
        if not force and now - self.last_publish < 0.2:
            return
        self.last_publish = now
        try:
            self._store("queue", self.tasks)
        except sqlite3.Error:
            self.state_failed = True
            for control in (*self.controls.values(), *self.node_controls.values()):
                control.set()
            for future in self.futures.values():
                if not future.done():
                    future.set_exception(DesktopError("STATE_WRITE_FAILED"))
            self.condition.notify_all()
            self.emit("transfer:error", {"message": "STATE_WRITE_FAILED"})
            return
        self.emit("transfer:queue-updated", self.queue())

    def _aggregate(self, task):
        for node in reversed(task["nodes"]):
            if node["kind"] != "folder":
                continue
            children = [n for n in task["nodes"] if n["parentId"] == node["id"]]
            if children:
                node["bytesTotal"] = sum(n["bytesTotal"] for n in children)
                node["bytesDone"] = sum(n["bytesDone"] for n in children)
                statuses = {n["status"] for n in children}
                node["status"] = self._status(statuses)
                node["progress"] = (node["bytesDone"] / node["bytesTotal"] * 100
                                    if node["bytesTotal"] else (100 if statuses <= FINISHED else 0))
                node["error"] = next((n["error"] for n in children if n["error"]), None)
            if node.get("_directoryError"):
                node["status"], node["error"] = "error", node["_directoryError"]
            if node.get("_canceled"):
                node["status"] = "canceled"
            elif node.get("_manualPaused") and node["status"] not in FINISHED:
                node["status"] = "paused"
        root = task["nodes"][0]
        task.update(status=root["status"], bytesDone=root["bytesDone"], bytesTotal=root["bytesTotal"],
                    progress=root["progress"], updatedAt=int(time.time() * 1000))

    @staticmethod
    def _status(statuses):
        if statuses <= {"done", "instant"}:
            return "done"
        for status in ("transferring", "hashing", "pending", "error", "paused", "canceled"):
            if status in statuses:
                return status
        return "done"

    def _update(self, task, node, status=None, **values):
        with self.lock:
            # Commit returned server identifiers even when pause raced the response.
            node.update(values)
            if status and not (node.get("_manualPaused") and status in ("hashing", "transferring", "done", "instant")):
                node["status"] = status
            self._aggregate(task)
            durable = status is not None or bool(values.keys() & {
                "_uncertain", "uploadId", "remoteFileId", "remoteParentId", "md5Hash", "chunkSize", "totalChunks", "_directoryReady", "_creatingDirectory"})
            self._publish(force=durable)
            if task["direction"] == "upload" and node["kind"] == "file":
                self.emit("desktop-upload:item-updated", {
                    "id": node["id"], "batchId": task["id"], "teamId": task.get("_teamId"),
                    "fileName": node["name"], "displayName": node["displayPath"],
                    "parentId": node["remoteParentId"],
                    "status": "uploading" if node["status"] == "transferring" else node["status"],
                    "progress": node["progress"], "chunkProgress": f'{node["completedChunks"]}/{node["totalChunks"]}',
                    "error": node["error"], "uploadId": node["uploadId"], "fileSize": node["bytesTotal"],
                    "md5Hash": node["md5Hash"], "totalChunks": node["totalChunks"], "resumable": False,
                })

    def _node(self, task, name, kind="file", parent=None, **values):
        path = (parent["path"] if parent else []) + [name]
        node = dict(id=uuid.uuid4().hex, taskId=task["id"], parentId=parent["id"] if parent else None,
                    direction=task["direction"], kind=kind, name=name, displayPath="/".join(path),
                    path=path, status="pending", progress=0, bytesDone=0, bytesTotal=0, error=None,
                    localPath=None, targetPath=None, remoteFileId=None, remoteParentId=task["baseParentId"],
                    uploadId=None, md5Hash=None, totalChunks=0, completedChunks=0, chunkSize=0,
                    _manualPaused=False)
        node.update(values)
        task["nodes"].append(node)
        return node

    def _task(self, name, direction, request):
        base = self.checked_base(request["apiBaseUrl"])
        now = int(time.time() * 1000)
        match = re.search(r"/teams/([1-9][0-9]*)$", base)
        return dict(id=uuid.uuid4().hex, direction=direction, rootNodeId="", name=name,
                    status="pending", progress=0, bytesDone=0, bytesTotal=0, createdAt=now, updatedAt=now,
                    apiBaseUrl=base, baseParentId=request.get("parentId"), nodes=[],
                    _account=self.session.owner, _server=self.api_base,
                    _generation=self.session.generation,
                    _teamId=int(match[1]) if match else None)

    def _add(self, task, context=None):
        with self.lock:
            if context is not None:
                self.require_context(context)
            if (self.session.owner != task["_account"] or self.api_base != task["_server"]
                    or self.session.generation != task["_generation"]):
                raise DesktopError("SESSION_CHANGED")
            task["rootNodeId"] = task["nodes"][0]["id"]
            self._aggregate(task)
            self.tasks.append(task)
            self._publish()
            if self.state_failed:
                raise DesktopError("STATE_WRITE_FAILED")
            self._spawn(task)
        return public(task)

    def upload_files(self, paths, request, folder=False, context=None):
        if context is not None:
            self.require_context(context)
        sources = [Path(p) for p in paths]
        if not sources:
            raise DesktopError("NO_FILES")
        task = self._task(sources[0].name if len(sources) == 1 else f"{len(sources)} files", "upload", request)
        seen = set()
        def visit(source, parent=None):
            if source.is_symlink() or source.is_junction() or not source.exists():
                raise DesktopError("INVALID_SOURCE")
            absolute = source.resolve(strict=True)
            if str(absolute).casefold() in seen:
                raise DesktopError("DUPLICATE_SOURCE")
            seen.add(str(absolute).casefold())
            if source.is_dir():
                node = self._node(task, source.name, "folder", parent, localPath=str(absolute))
                for child in sorted(source.iterdir(), key=lambda p: p.name.casefold()):
                    visit(child, node)
            elif source.is_file():
                stat = source.stat()
                self._node(task, source.name, parent=parent, localPath=str(absolute),
                           bytesTotal=stat.st_size, _mtime=stat.st_mtime_ns)
            else:
                raise DesktopError("INVALID_SOURCE")
        if folder:
            if len(sources) != 1 or not sources[0].is_dir():
                raise DesktopError("INVALID_SOURCE")
            visit(sources[0])
        elif len(sources) == 1:
            if not sources[0].is_file():
                raise DesktopError("INVALID_SOURCE")
            visit(sources[0])
        else:
            root = self._node(task, task["name"], "folder", _virtual=True)
            for source in sources:
                if not source.is_file():
                    raise DesktopError("INVALID_SOURCE")
                visit(source, root)
        if context is not None:
            self.require_context(context)
        return self._add(task, context)

    def download(self, target, request, tree=None, context=None):
        if context is not None:
            self.require_context(context)
        target = Path(target).absolute()
        self._safe_target(target)
        task = self._task(request["fileName"] if tree is None else tree["fileName"], "download", request)
        def visit(item, destination, parent=None):
            if item.get("fileType") not in ("file", "folder"):
                raise DesktopError("INVALID_DOWNLOAD_TREE")
            if destination.exists() or destination.is_symlink():
                raise DesktopError("TARGET_EXISTS")
            node = self._node(task, destination.name, item["fileType"], parent,
                              remoteFileId=int(item["id"]), bytesTotal=int(item.get("fileSize", 0)),
                              targetPath=str(destination))
            if item["fileType"] == "folder":
                used = set()
                for child in item.get("children", []):
                    name = safe_name(child["fileName"])
                    stem, suffix = Path(name).stem, Path(name).suffix
                    count = 1
                    while name.casefold() in used:
                        name = f"{stem} ({count}){suffix}"
                        count += 1
                    used.add(name.casefold())
                    visit(child, destination / name, node)
        visit(tree or dict(id=request["fileId"], fileName=request["fileName"], fileType="file",
                           fileSize=request.get("fileSize", 0)), target)
        return self._add(task, context)

    def _find(self, identity):
        with self.lock:
            for task in self.tasks:
                if task["_account"] != self.session.owner or task["_server"] != self.api_base:
                    continue
                for node in task["nodes"]:
                    if identity in (node["id"], task["id"]):
                        return task, node
        raise DesktopError("TASK_NOT_FOUND")

    def _subtree(self, task, root):
        ids = {root["id"]}
        for node in task["nodes"]:
            if node["parentId"] in ids:
                ids.add(node["id"])
        return [n for n in task["nodes"] if n["id"] in ids]

    def pause(self, identity, cancel=False, context=None):
        with self.lock:
            if context is not None:
                self.require_context(context)
            task, node = self._find(identity)
            for child in self._subtree(task, node):
                if child["status"] not in FINISHED:
                    child["_manualPaused"] = True
                    if cancel:
                        child["_canceled"] = True
                    self.node_controls.get(child["id"], threading.Event()).set()
                    self._update(task, child, "canceled" if cancel else "paused")
            if node["id"] == task["rootNodeId"]:
                self.controls.get(task["id"], threading.Event()).set()
            self.condition.notify_all()
        return self.queue()

    def cancel(self, identity, context=None):
        with self.lock:
            if context is not None:
                self.require_context(context)
            task, node = self._find(identity)
            generation = self.session.generation
            selected = list(self._subtree(task, node))
            self.pause(identity, cancel=True)
            for child in selected:
                if child.get("_canceled") and child["id"] not in self.inflight:
                    if task["direction"] == "download" and child["kind"] == "file":
                        try:
                            self._safe_target(self._part(child))
                            self._part(child).unlink(missing_ok=True)
                        except OSError:
                            self._update(task, child, "error", error="CANCEL_CLEANUP_FAILED")
                            self.emit("transfer:error", {"message": "CANCEL_CLEANUP_FAILED"})
                    elif task["direction"] == "upload":
                        self.cleanup_executor.submit(self._cancel_remote, task, child, generation)
        return self.queue()

    def _cancel_remote(self, task, node, generation):
        if not node.get("uploadId"):
            return
        def check():
            self.session.access(task["_account"], generation)
        try:
            client = LiveClient(lambda: self.client(task["_account"], generation))
            response = self._request(client, "POST", f'{task["apiBaseUrl"]}/upload/{node["uploadId"]}/cancel', check)
            if response.status_code not in (404, 410):
                envelope(response)
        except Exception:
            if generation == self.session.generation:
                self.emit("transfer:error", {"message": "REMOTE_CANCEL_FAILED"})

    def resume(self, identity, token=None, api_base=None, context=None):
        with self.lock:
            if context is not None:
                self.require_context(context)
            task, node = self._find(identity)
            if api_base:
                self.checked_base(api_base)
            selected = self._subtree(task, node)
            parent = self._parent(task, node)
            while parent:
                if not parent.get("_directoryReady") or parent.get("_manualPaused") or parent.get("_canceled"):
                    raise DesktopError("PARENT_NOT_READY")
                parent = self._parent(task, parent)
            if any(n.get("_uncertain") for n in selected):
                raise DesktopError("RESULT_UNCERTAIN")
            for child in selected:
                if child["status"] not in FINISHED:
                    child["_manualPaused"] = False
                    child.pop("_directoryError", None)
                    child["status"], child["error"] = "pending", None
            self._aggregate(task)
            self._publish()
            self._spawn(task)
        return self.queue()

    def clear_finished(self, context=None):
        with self.lock:
            if context is not None:
                self.require_context(context)
            retained = []
            for task in self.tasks:
                running = task["id"] in self.futures and not self.futures[task["id"]].done()
                eligible = (task["_account"] == self.session.owner and task["_server"] == self.api_base
                            and all(n["status"] in FINISHED | {"error"} for n in task["nodes"]) and not running)
                if eligible and task["direction"] == "download":
                    try:
                        for node in task["nodes"]:
                            if node["kind"] == "file":
                                target = Path(node["targetPath"])
                                part = target.with_name(target.name + "." + node["id"] + ".part")
                                self._safe_target(part)
                                part.unlink(missing_ok=True)
                    except (OSError, DesktopError):
                        eligible = False
                        self.emit("transfer:error", {"message": "CANCEL_CLEANUP_FAILED"})
                if not eligible:
                    retained.append(task)
            self.tasks = retained
            ids = {t["id"] for t in retained}
            nodes = {n["id"] for t in retained for n in t["nodes"]}
            self.futures = {k: v for k, v in self.futures.items() if k in ids}
            self.controls = {k: v for k, v in self.controls.items() if k in ids}
            self.node_controls = {k: v for k, v in self.node_controls.items() if k in nodes}
            self.task_generations = {k: v for k, v in self.task_generations.items() if k in ids}
            self._publish()
        return self.queue()

    def _spawn(self, task):
        self._schedule(task)

    def _schedule(self, task):
        with self.condition:
            if self.closed:
                raise DesktopError("ENGINE_CLOSED")
            if self.state_failed:
                raise DesktopError("STATE_WRITE_FAILED")
            previous = self.futures.get(task["id"])
            if previous is None or previous.done():
                self.futures[task["id"]] = Future()
            if self.controls.get(task["id"], threading.Event()).is_set() or task["id"] not in self.controls:
                self.controls[task["id"]] = threading.Event()
            self.task_generations[task["id"]] = self.session.generation
            self.condition.notify_all()

    def _check(self, task, node, stop, generation):
        if self.state_failed:
            raise DesktopError("STATE_WRITE_FAILED")
        if stop.is_set() or node.get("_manualPaused") or self.closed:
            raise Interrupted()
        self.session.access(task["_account"], generation)
        if task["_server"] != self.api_base:
            raise DesktopError("SESSION_CHANGED")

    def _run(self, task, stop, generation):
        # Synchronous entry for isolated engine drivers; production uses _spawn.
        self._schedule(task)
        return self.futures[task["id"]].result()

    def _ready(self, task, node):
        if node["id"] in self.inflight or node.get("_manualPaused") or node.get("_canceled") or node.get("_uncertain"):
            return False
        if node["kind"] == "folder":
            if node.get("_directoryReady") or node.get("_directoryError"):
                return False
        elif node["status"] != "pending":
            return False
        parent = self._parent(task, node)
        return parent is None or (parent.get("_directoryReady") and not parent.get("_manualPaused") and not parent.get("_canceled"))

    def _schedule_loop(self):
        with self.condition:
            while True:
                dispatched = False
                tasks = self.tasks[self.schedule_cursor:] + self.tasks[:self.schedule_cursor]
                for task in tasks:
                    future = self.futures.get(task["id"])
                    if future is None or future.done():
                        continue
                    generation = self.task_generations[task["id"]]
                    stop = self.controls[task["id"]]
                    valid = (not self.closed and not self.state_failed and not stop.is_set() and task["_account"] == self.session.owner
                             and task["_server"] == self.api_base and generation == self.session.generation)
                    if valid:
                        for node in task["nodes"]:
                            if not self._ready(task, node):
                                continue
                            directory = node["kind"] == "folder"
                            direction = task["direction"]
                            if directory:
                                if self.directory_active >= 2:
                                    continue
                                self.directory_active += 1
                            else:
                                if self.active[direction] >= self.settings["maxConcurrent" + direction.capitalize() + "s"]:
                                    continue
                                self.active[direction] += 1
                            child_stop = threading.Event()
                            self.node_controls[node["id"]] = child_stop
                            self.inflight[node["id"]] = task["id"]
                            self.executor.submit(self._run_node, task, node, child_stop, stop, generation)
                            dispatched = True
                    running = any(owner == task["id"] for owner in self.inflight.values())
                    ready = valid and any(self._ready(task, node) for node in task["nodes"])
                    if not running and not ready:
                        self._aggregate(task)
                        self._publish()
                        if not future.done():
                            future.set_result(None)
                        if task["direction"] == "upload" and valid:
                            self.emit("desktop-upload:batch-completed", None)
                if self.tasks:
                    self.schedule_cursor = (self.schedule_cursor + 1) % len(self.tasks)
                if self.closed and not self.inflight:
                    return
                if not dispatched:
                    self.condition.wait()

    def _run_node(self, task, node, child_stop, stop, generation):
        try:
            self._progress_check(task, node, child_stop, stop, generation)
            client = LiveClient(lambda: self.client(task["_account"], generation, stop))
            client.check = lambda: self._progress_check(task, node, child_stop, stop, generation)
            if node["kind"] == "folder":
                self._directory(task, node, client)
            elif task["direction"] == "upload":
                self._upload(task, node, client, child_stop, stop, generation)
            else:
                self._download(task, node, client, child_stop, stop, generation)
        except Interrupted:
            pass
        except Exception as error:
            with self.lock:
                if not stop.is_set() and not child_stop.is_set() and generation == self.session.generation:
                    code = self._error_code(error)
                    if node["kind"] == "folder":
                        node["_directoryError"] = code
                        for descendant in self._subtree(task, node)[1:]:
                            if descendant["status"] not in FINISHED:
                                descendant["status"], descendant["error"] = "error", "PARENT_NOT_READY"
                    self._update(task, node, "error", error=code)
                    self.emit("transfer:error", {"message": code})
        finally:
            if task["direction"] == "upload" and node.get("_canceled"):
                self.cleanup_executor.submit(self._cancel_remote, task, node, generation)
            with self.condition:
                if node["kind"] == "folder":
                    self.directory_active -= 1
                else:
                    self.active[task["direction"]] -= 1
                self.inflight.pop(node["id"], None)
                if task["direction"] == "download" and node.get("_canceled"):
                    try:
                        self._safe_target(self._part(node))
                        self._part(node).unlink(missing_ok=True)
                    except (OSError, DesktopError):
                        node["status"], node["error"] = "error", "CANCEL_CLEANUP_FAILED"
                self._aggregate(task)
                self._publish()
                self.condition.notify_all()

    @staticmethod
    def _error_code(error):
        import errno
        if isinstance(error, DesktopError):
            return str(error)
        if isinstance(error, OSError):
            if error.errno == errno.ENOSPC:
                return "DISK_FULL"
            if isinstance(error, PermissionError) or getattr(error, "winerror", None) in (5, 32, 33):
                return "FILE_UNAVAILABLE"
        if isinstance(error, TRANSIENT):
            return "NETWORK_RETRY_EXHAUSTED"
        return "TRANSFER_IO_FAILED"

    @staticmethod
    def _part(node):
        target = Path(node["targetPath"])
        return target.with_name(target.name + "." + node["id"] + ".part")

    @staticmethod
    def _safe_target(path):
        for candidate in (Path(path), *Path(path).parents):
            if candidate.is_symlink() or candidate.is_junction():
                raise DesktopError("INVALID_TARGET")

    def _wait(self, seconds, check):
        deadline = time.monotonic() + seconds
        with self.condition:
            while True:
                check()
                left = deadline - time.monotonic()
                if left <= 0:
                    return
                self.condition.wait(min(left, 0.1))

    def _request(self, client, method, url, check, **kwargs):
        return retry_request(client, method, url, check, self._wait, **kwargs)

    def _create(self, task, node, client, url, check, **kwargs):
        check()
        self._update(task, node, _uncertain=True)
        try:
            check()
        except (Interrupted, DesktopError):
            self._update(task, node, _uncertain=False)
            raise
        try:
            response = client.request("POST", url, **kwargs)
            if response.status_code >= 500 or response.status_code == 408:
                raise DesktopError("RESULT_UNCERTAIN")
            data = envelope(response)
            if not isinstance(data, dict):
                raise DesktopError("RESULT_UNCERTAIN")
        except (*TRANSIENT, DesktopError) as error:
            if isinstance(error, TRANSIENT) or str(error) in ("INVALID_API_RESPONSE", "RESULT_UNCERTAIN"):
                raise DesktopError("RESULT_UNCERTAIN") from None
            self._update(task, node, _uncertain=False)
            raise
        return data

    def _parent(self, task, node):
        return next((n for n in task["nodes"] if n["id"] == node["parentId"]), None)

    def _directory(self, task, node, client):
        check = client.check
        check()
        parent = self._parent(task, node)
        values = {"_directoryReady": True, "_uncertain": False}
        if task["direction"] == "upload":
            parent_id = parent["remoteFileId"] if parent else task["baseParentId"]
            if parent and parent_id is None and not parent.get("_virtual"):
                raise DesktopError("PARENT_NOT_READY")
            if node.get("_virtual"):
                values["remoteFileId"] = task["baseParentId"]
            elif node["remoteFileId"] is None:
                data = self._create(task, node, client, task["apiBaseUrl"] + "/files/folder", check,
                                    json={"parentId": parent_id, "folderName": node["name"]})
                if type(data.get("id")) is not int or data["id"] <= 0:
                    raise DesktopError("RESULT_UNCERTAIN")
                values["remoteFileId"] = data["id"]
        else:
            path = Path(node["targetPath"])
            self._safe_target(path)
            self._update(task, node, _creatingDirectory=True)
            check()
            path.mkdir(parents=True, exist_ok=False)
            values["_creatingDirectory"] = False
        self._update(task, node, "done", progress=100, **values)

    def _progress_check(self, task, node, child_stop, stop, generation):
        if child_stop.is_set():
            raise Interrupted()
        self._check(task, node, stop, generation)

    def _hash(self, path, check):
        md5 = hashlib.md5()
        with path.open("rb") as source:
            while content := source.read(1024 * 1024):
                check()
                md5.update(content)
        check()
        return md5.hexdigest()

    def _rate_pump(self, direction):
        now = time.monotonic()
        limit = self.settings[direction + "LimitBytesPerSec"]
        tickets = self.rate_tickets[direction]
        credit = max(0, now - self.rate_last[direction]) * (limit or 0)
        self.rate_last[direction] = now
        while tickets:
            ticket = tickets[0]
            if limit and credit < ticket["remaining"]:
                ticket["remaining"] -= credit
                break
            credit -= ticket["remaining"]
            ticket["done"] = True
            tickets.pop(0)
            self.condition.notify_all()

    def _throttle(self, direction, count, started, stop):
        with self.condition:
            self._rate_pump(direction)
            if not self.settings[direction + "LimitBytesPerSec"]:
                return
            ticket = {"remaining": count, "done": False}
            tickets = self.rate_tickets[direction]
            tickets.append(ticket)
            while True:
                self._rate_pump(direction)
                if stop.is_set() or self.closed:
                    # Upload has not sent these bytes yet. Downloads already
                    # received their bytes, so retain their bandwidth debt.
                    if direction == "upload" and ticket in tickets:
                        tickets[:] = [item for item in tickets if item is not ticket]
                    self.condition.notify_all()
                    raise Interrupted()
                if ticket["done"]:
                    return
                self.condition.wait(.1)

    def _upload(self, task, node, client, child_stop, stop, generation):
        check = lambda: self._progress_check(task, node, child_stop, stop, generation)
        path = Path(node["localPath"])
        if path.is_symlink() or path.is_junction() or path.stat().st_size != node["bytesTotal"] or path.stat().st_mtime_ns != node["_mtime"]:
            raise DesktopError("SOURCE_CHANGED")
        if node.get("remoteFileId") is not None:
            envelope(self._request(client, "GET", f'{task["apiBaseUrl"]}/files/{node["remoteFileId"]}', check))
            self._update(task, node, "done", bytesDone=node["bytesTotal"], progress=100)
            return
        parent = self._parent(task, node)
        if parent:
            if parent["remoteFileId"] is None and not parent.get("_virtual"):
                raise DesktopError("PARENT_NOT_READY")
            node["remoteParentId"] = parent["remoteFileId"]
        self._update(task, node, "hashing")
        checksum = self._hash(path, check)
        if node["md5Hash"] and node["md5Hash"] != checksum:
            raise DesktopError("SOURCE_CHANGED")
        self._update(task, node, md5Hash=checksum)
        base = task["apiBaseUrl"]
        if not node["uploadId"]:
            data = self._create(task, node, client, base + "/upload/init", check, json={
                "parentId": node["remoteParentId"], "fileName": node["name"],
                "fileSize": node["bytesTotal"], "md5Hash": checksum})
            if data.get("mode") == "INSTANT" and data.get("fileId"):
                self._update(task, node, "instant", _uncertain=False, remoteFileId=data["fileId"],
                             bytesDone=node["bytesTotal"], progress=100)
                return
            if (data.get("mode") != "MULTIPART" or not data.get("sessionId") or
                    data.get("chunkSize", 0) <= 0 or data.get("totalChunks") !=
                    math.ceil(node["bytesTotal"] / data["chunkSize"])):
                raise DesktopError("RESULT_UNCERTAIN")
            self._update(task, node, uploadId=data["sessionId"], chunkSize=data["chunkSize"],
                         totalChunks=data["totalChunks"], _uncertain=False)
        try:
            status = envelope(self._request(client, "GET", f'{base}/upload/{node["uploadId"]}/status', check))
        except DesktopError as error:
            if str(error) in ("API_419006", "HTTP_404", "HTTP_410"):
                raise DesktopError("UPLOAD_SESSION_UNAVAILABLE") from None
            raise
        missing = status.get("missingChunks")
        if (not isinstance(missing, list) or len(set(missing)) != len(missing) or
                any(type(n) is not int or n < 0 or n >= node["totalChunks"] for n in missing)):
            raise DesktopError("INVALID_UPLOAD_STATUS")
        started, sent = time.monotonic(), 0
        with path.open("rb") as source:
            for i, number in enumerate(missing):
                check()
                source.seek(number * node["chunkSize"])
                content = source.read(node["chunkSize"])
                def charge():
                    check()
                    self._throttle("upload", len(content), started, child_stop)
                envelope(retry_request(client, "POST", f'{base}/upload/{node["uploadId"]}/chunk', check, self._wait, before_attempt=charge,
                    params={"chunkNumber": number, "chunkMd5": hashlib.md5(content).hexdigest()},
                    files={"file": ("part", content, "application/octet-stream")}))
                check()
                completed = node["totalChunks"] - len(missing) + i + 1
                self._update(task, node, "transferring", completedChunks=completed,
                             bytesDone=min(node["bytesTotal"], completed * node["chunkSize"]),
                             progress=completed / max(1, node["totalChunks"]) * 100)
                sent += len(content)
        check()
        if path.stat().st_size != node["bytesTotal"] or path.stat().st_mtime_ns != node["_mtime"]:
            raise DesktopError("SOURCE_CHANGED")
        data = envelope(self._request(client, "POST", f'{base}/upload/{node["uploadId"]}/complete', check))
        self._update(task, node, "done", remoteFileId=data["fileId"], progress=100, bytesDone=node["bytesTotal"])

    def _download(self, task, node, client, child_stop, stop, generation):
        check = lambda: self._progress_check(task, node, child_stop, stop, generation)
        target = Path(node["targetPath"])
        self._safe_target(target)
        if target.exists():
            raise DesktopError("TARGET_EXISTS")
        target.parent.mkdir(parents=True, exist_ok=True)
        metadata = envelope(self._request(client, "GET", f'{task["apiBaseUrl"]}/files/{node["remoteFileId"]}', check))
        size, md5 = metadata["fileSize"], metadata.get("md5Hash")
        if type(size) is not int or size < 0:
            raise DesktopError("INVALID_API_RESPONSE")
        if node["md5Hash"] and (node["md5Hash"] != md5 or node["bytesTotal"] != size):
            raise DesktopError("REMOTE_CHANGED")
        self._update(task, node, bytesTotal=size, md5Hash=md5)
        part = self._part(node)
        for attempt in range(3):
            check()
            self._safe_target(part)
            start = part.stat().st_size if part.exists() else 0
            response = None
            try:
                with client.stream("GET", f'{task["apiBaseUrl"]}/download/{node["remoteFileId"]}',
                                   headers={"Range": f"bytes={start}-"} if start else {}) as response:
                    if response.status_code in RETRY_STATUS:
                        response.read()
                        raise httpx.ReadError("RETRYABLE_STATUS")
                    if response.status_code == 416:
                        if response.headers.get("content-range") != f"bytes */{start}" or start != size:
                            raise DesktopError("INVALID_RANGE")
                        if not part.exists():
                            part.touch()
                    else:
                        if response.status_code not in (200, 206):
                            response.read()
                            envelope(response)
                            raise DesktopError("INVALID_DOWNLOAD_RESPONSE")
                        if "application/json" in response.headers.get("content-type", "") and not response.headers.get("content-disposition"):
                            response.read()
                            envelope(response)
                            raise DesktopError("INVALID_DOWNLOAD_RESPONSE")
                        if response.status_code == 206:
                            match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("content-range", ""))
                            if not match or int(match[1]) != start or int(match[3]) != size or int(match[2]) != size - 1:
                                raise DesktopError("INVALID_RANGE")
                        else:
                            start = 0
                        self._update(task, node, "transferring", bytesDone=start)
                        received = 0
                        self._safe_target(part)
                        with part.open("ab" if start else "wb") as output:
                            for content in response.iter_bytes():
                                check()
                                received += len(content)
                                if start + received > size:
                                    raise DesktopError("INVALID_DOWNLOAD_SIZE")
                                self._throttle("download", len(content), 0, child_stop)
                                check()
                                output.write(content)
                                self._update(task, node, bytesDone=start + received,
                                             progress=(start + received) / max(size, 1) * 100)
                break
            except TRANSIENT:
                if attempt == 2:
                    raise DesktopError("NETWORK_RETRY_EXHAUSTED") from None
                self._wait(retry_delay(response, attempt), check)
        check()
        if part.stat().st_size != size:
            raise DesktopError("DOWNLOAD_INCOMPLETE")
        if md5 and self._hash(part, check) != md5:
            raise DesktopError("DOWNLOAD_CHECKSUM_MISMATCH")
        with self.lock:
            check()
            self._safe_target(target)
            if target.exists():
                raise DesktopError("TARGET_EXISTS")
            if os.name == "nt":
                os.rename(part, target)
            else:
                os.link(part, target)
                part.unlink()
            self._update(task, node, "done", progress=100, bytesDone=size)

    def close(self):
        with self.lock:
            self.closed = True
            self._stop_all()
            self.session.replace(self.api_base)
            self._publish()
        with self.condition:
            self.condition.notify_all()
        self.executor.shutdown(wait=True, cancel_futures=False)
        self.scheduler.join()
        self.cleanup_executor.shutdown(wait=True)
        self.db.close()
