"""Explicit real-backend acceptance; never starts Docker or changes rate limits."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tests"))
from cdp import Cdp
from engine import Engine
from network import DesktopError
from live_support import Api, env_values, expired_token, wait_for
from preview_checks import PreviewChecks


def need(condition, code):
    if not condition:
        raise DesktopError(code)


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "md5").hexdigest()


class Validation(PreviewChecks):
    def __init__(self, base, office, native_only=False, port=5194, debug_port=9226, offscreen=False,
                 python_executable=None, web_root=None, process_env=None):
        self.id = secrets.token_hex(5)
        self.out = ROOT.parent / "test-results/desktop" / self.id
        self.out.mkdir(parents=True)
        self.api = Api(self.id, base)
        self.office_enabled = office
        self.native_only = native_only
        self.port, self.debug_port, self.offscreen = port, debug_port, offscreen
        self.python_executable, self.web_root, self.process_env = python_executable, web_root, process_env
        self.engine = None
        self.process = self.page = None
        self.checks, self.metrics, self.secrets = [], {}, []

    def step(self, name, operation):
        start = time.monotonic()
        try:
            result = operation()
            self.checks.append({"name": name, "status": "passed", "seconds": round(time.monotonic()-start, 2), "result": result})
            print(name, "passed", flush=True)
        except Exception as error:
            code = str(error) if isinstance(error, DesktopError) else type(error).__name__
            self.checks.append({"name": name, "status": "failed", "code": code})
            print(name, "failed", code, flush=True)
            if self.page:
                try:
                    self.page.screenshot(self.out / (name + "-failed.png"))
                    tree = self.page.command("Page.getFrameTree")
                    def frames(item):
                        frame = item["frame"]
                        result = [{"mime": frame.get("mimeType"), "scheme": frame.get("url", "").split(":")[0]}]
                        for child in item.get("childFrames", []):
                            result.extend(frames(child))
                        return result
                    print("FRAME_TYPES", json.dumps(frames(tree["frameTree"])), flush=True)
                except Exception:
                    pass
            raise

    def setup(self):
        self.owner, self.viewer = self.api.user("owner"), self.api.user("viewer")
        for user in (self.owner, self.viewer):
            self.secrets.extend(user.get(k, "") for k in ("password", "accessToken", "refreshToken"))
        self.team = self.api.call("POST", "/teams", self.owner, json={"name": "qtd_" + self.id})["id"]
        self.api.teams.append((self.owner, self.team))
        self.api.call("POST", f"/teams/{self.team}/members", self.owner,
                      json={"username": self.viewer["username"], "role": "VIEWER"})
        self.engine = Engine(self.out / "native", self.api.base)
        self.engine.authenticate(self.owner["accessToken"], self.api.base)
        return {"accounts": 2, "team": True}

    def finish(self, task):
        self.engine.futures[task["id"]].result(timeout=150)
        current = self.engine._find(task["id"])[0]
        need(current["status"] in ("done", "instant"), "TRANSFER_" + str(current["nodes"][0].get("error") or current["status"]))
        return current

    def request(self, team=None):
        return {"parentId": None, "apiBaseUrl": self.api.base + (f"/teams/{team}" if team else "")}

    def restart(self):
        self.engine.close()
        self.engine = Engine(self.out / "native", self.api.base)
        self.engine.authenticate(self.owner["accessToken"], self.api.base)

    def transfers(self):
        self.big_path = self.out / "large.bin"
        block = os.urandom(1024 * 1024)
        with self.big_path.open("wb") as stream:
            for _ in range(64):
                stream.write(block)
        self.engine.save_settings({"uploadLimitBytesPerSec": 4 * 1024 * 1024})
        task = self.engine.upload_files([self.big_path], self.request())
        internal = self.engine._find(task["id"])[0]
        wait_for(lambda: internal["nodes"][0]["completedChunks"] >= 1 or internal["status"] == "error")
        self.engine.pause(task["id"])
        self.engine.futures[task["id"]].result(timeout=35)
        self.restart()
        self.engine.save_settings({})
        self.engine.resume(task["id"])
        uploaded = self.finish(task)
        self.big_file = uploaded["nodes"][0]["remoteFileId"]
        target = self.out / "download.bin"
        self.engine.save_settings({"downloadLimitBytesPerSec": 4 * 1024 * 1024})
        download = self.engine.download(target, {**self.request(), "fileId": self.big_file, "fileName": "large.bin"})
        item = self.engine._find(download["id"])[0]
        wait_for(lambda: item["nodes"][0]["bytesDone"] >= 1024 * 1024 or item["status"] == "error")
        self.engine.pause(download["id"])
        self.engine.futures[download["id"]].result(timeout=35)
        self.restart()
        self.engine.save_settings({})
        self.engine.resume(download["id"])
        self.finish(download)
        need(digest(target) == digest(self.big_path), "MD5_MISMATCH")
        return {"sizeMiB": 64, "uploadRestart": True, "downloadRestart": True, "md5": True}

    def directories(self):
        source = self.out / "folder"
        (source / "empty").mkdir(parents=True)
        (source / "nested").mkdir()
        (source / "nested" / "unicode.txt").write_text("desktop directory")
        (source / "zero.txt").touch()
        task = self.finish(self.engine.upload_files([source], self.request(), folder=True))
        root_id = task["nodes"][0]["remoteFileId"]
        def tree(identity):
            value = self.api.call("GET", f"/files/{identity}", self.owner)
            if value["fileType"] == "folder":
                records = self.api.call("GET", "/files", self.owner, params={"parentId": identity, "page": 1, "size": 100})
                value["children"] = [tree(item["id"]) for item in records["records"]]
            return value
        target = self.out / "folder-download"
        self.finish(self.engine.download(target, self.request(), tree=tree(root_id)))
        need((target / "empty").is_dir() and (target / "nested/unicode.txt").read_text() == "desktop directory",
             "DIRECTORY_CONTENT_MISMATCH")
        a, b = self.out / "batch-a.txt", self.out / "batch-b.txt"
        a.write_bytes(b"batch-a")
        b.write_bytes(b"batch-b")
        self.finish(self.engine.upload_files([a, b], self.request(self.team)))
        return {"recursiveUploadDownload": True, "emptyDirectory": True, "teamBatch": True}

    def native_refresh(self):
        stale = expired_token(self.owner["accessToken"], env_values(ROOT.parent))
        self.secrets.append(stale)
        self.engine.session.replace(self.api.base, str(self.owner["id"]), stale)
        def event(name, _):
            if name == "session:refresh-required":
                result = self.api.call("POST", "/auth/refresh", json={"refreshToken": self.owner["refreshToken"]})
                self.owner.update(result)
                self.secrets.extend(result.values())
                self.engine.authenticate(self.owner["accessToken"], self.api.base)
        self.engine.emit = event
        self.finish(self.engine.download(self.out / "after-refresh.bin",
            {**self.request(), "fileId": self.big_file, "fileName": "large.bin"}))
        return {"real401Refresh": True}

    def revoked_permission(self):
        file = self.api.upload(self.owner, "permission.bin", os.urandom(2 * 1024 * 1024), self.team)
        engine = Engine(self.out / "viewer-native", self.api.base)
        try:
            engine.authenticate(self.viewer["accessToken"], self.api.base)
            engine.save_settings({"downloadLimitBytesPerSec": 64 * 1024})
            target = self.out / "forbidden.bin"
            task = engine.download(target, {**self.request(self.team), "fileId": file["id"], "fileName": "permission.bin"})
            item = engine._find(task["id"])[0]
            wait_for(lambda: item["nodes"][0]["bytesDone"] > 0 or item["status"] == "error")
            engine.pause(task["id"])
            engine.futures[task["id"]].result(timeout=35)
            self.api.call("DELETE", f"/teams/{self.team}/members/{self.viewer['id']}", self.owner)
            engine.resume(task["id"])
            engine.futures[task["id"]].result(timeout=35)
            need(item["status"] == "error" and item["nodes"][0]["error"] == "PERMISSION_DENIED"
                 and not target.exists(), "REVOKED_PERMISSION_NOT_ENFORCED")
        finally:
            engine.close()
            self.api.call("POST", f"/teams/{self.team}/members", self.owner,
                          json={"username": self.viewer["username"], "role": "VIEWER"})
        return {"revocationStopsResume": True, "finalFileNotPublished": True}

    def launch(self, expect_login=True, ready_expression=None):
        # Never attach to another client's debug port or close its page later.
        if self.port == self.debug_port:
            raise DesktopError("TEST_PORTS_MUST_DIFFER")
        for port in (self.port, self.debug_port):
            with socket.socket() as probe:
                try:
                    probe.bind(("127.0.0.1", port))
                except OSError:
                    raise DesktopError("TEST_PORT_UNAVAILABLE") from None
        env = {**(getattr(self, 'process_env', None) or os.environ), "PYTHONUTF8": "1", "QT_MEDIA_BACKEND": "ffmpeg"}
        if self.offscreen:
            env.update(QT_QPA_PLATFORM="offscreen", QTWEBENGINE_CHROMIUM_FLAGS="--disable-gpu")
        command = [str(getattr(self, 'python_executable', None) or sys.executable), str(ROOT / "app.py"),
            "--profile", str(self.out / "profile"), "--api-base", self.api.base,
            "--port", str(self.port), "--debug-port", str(self.debug_port)]
        if getattr(self, 'web_root', None):
            command += ['--web-root', str(self.web_root)]
        self.process = subprocess.Popen(command,
            cwd=ROOT, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.page = Cdp(self.debug_port, expected_origin=f"http://127.0.0.1:{self.port}")
        self.page.wait(ready_expression or ("!!document.querySelector('.login-page__form input')" if expect_login else
                       "location.hash.includes('/files') && !!document.querySelector('[data-testid^=file-card]')"), 30)
        self.page.invoke("get_desktop_settings")
        self.page.evaluate("window.__desktopEvents=0;window.addEventListener('bcd:event',()=>window.__desktopEvents++)")
        return {"vue": True, "bridge": True}

    def login(self):
        for selector, value in ((".login-page__form input[type=text]", self.owner["username"]),
                                (".login-page__form input[type=password]", self.owner["password"])):
            self.page.click(selector)
            self.page.command("Input.insertText", {"text": value})
        self.page.click("form button[type=submit]")
        self.page.wait("location.hash.includes('/files') && !!document.querySelector('[data-testid^=file-card]')", 40)
        self.page.evaluate("window.__validationReloadMarker=true")
        self.page.command("Page.reload")
        self.page.wait("!window.__validationReloadMarker && location.hash.includes('/files') && !!document.querySelector('[data-testid^=file-card]')", 40)
        for key in ("accessToken", "refreshToken"):
            self.owner[key] = self.page.evaluate("localStorage.getItem(" + json.dumps(key) + ")")
            self.secrets.append(self.owner[key])
        self.page.invoke("save_desktop_settings", {"settings": {"defaultDownloadDir": str(self.out / "ui-downloads")}})
        self.page.invoke("start_desktop_download_file", {"request": {
            **self.request(), "fileId": self.big_file, "fileName": "from-ui.bin", "token": self.owner["accessToken"]}})
        wait_for(lambda: (self.out / "ui-downloads/from-ui.bin").exists(), 120)
        need(digest(self.out / "ui-downloads/from-ui.bin") == digest(self.big_path), "UI_DOWNLOAD_CHECKSUM")
        return {"login": True, "reload": True, "nativeDownload": True}

    def session_refresh(self):
        before = self.page.evaluate("localStorage.getItem('accessToken')")
        self.secrets.append(before)
        self.page.evaluate("window.dispatchEvent(new CustomEvent('bcd:event',{detail:JSON.stringify({name:'session:refresh-required',payload:{}})}));true")
        self.page.wait("localStorage.getItem('accessToken')!==" + json.dumps(before))
        for key in ("accessToken", "refreshToken"):
            self.owner[key] = self.page.evaluate("localStorage.getItem(" + json.dumps(key) + ")")
            self.secrets.append(self.owner[key])
        # Native queue visibility requires successful session synchronization.
        self.page.wait("!!localStorage.getItem('accessToken')")
        self.page.invoke("get_transfer_queue")
        return {"webToNativeRefresh": True}

    def native_video(self):
        import imageio_ffmpeg
        path = self.out / "hd.mp4"
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi",
            "-i", "testsrc=size=1920x1080:rate=24", "-f", "lavfi", "-i", "sine=frequency=440",
            "-t", "8", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-movflags", "+faststart", str(path)],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        file = self.api.upload(self.owner, "video_" + self.id + ".mp4", path.read_bytes())
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        self.page.evaluate(store + ".openPreview(" + json.dumps(file) + ")")
        def playing():
            data = self.page.invoke("desktop:diagnostics")
            return len(data["players"]) == 1 and data["players"][0]["position"] > 1000 and data["players"][0]["error"] == "NoError"
        wait_for(playing, 30)
        self.page.invoke("session:clear")
        wait_for(lambda: not self.page.invoke("desktop:diagnostics")["players"], 10)
        token = self.page.evaluate("localStorage.getItem('accessToken')")
        self.page.invoke("session:sync", {"apiBaseUrl": self.api.base, "token": token})
        return {"realBackend1080p": True, "nativeWindow": True, "sessionClearClosesPlayer": True}

    def security(self):
        need(self.page.evaluate("typeof window.qt==='undefined'"), "QT_EXPOSED_IN_MAIN_WORLD")
        need(self.page.evaluate("window.__BCD_RUNTIME__?.kind==='qt' && Object.isFrozen(window.__BCD_RUNTIME__) && Object.keys(window.__BCD_RUNTIME__).sort().join(',')==='apiBaseUrl,kind,webBaseUrl'"), 'PUBLIC_RUNTIME_CONFIGURATION_INVALID')
        need(self.page.evaluate("(() => {const frame=document.createElement('iframe');document.body.append(frame);const isolated=typeof frame.contentWindow.__BCD_RUNTIME__==='undefined' && typeof frame.contentWindow.qt==='undefined';frame.remove();return isolated})()"), 'RUNTIME_EXPOSED_IN_SUBFRAME')
        try:
            self.page.invoke("download_desktop_file", {})
        except RuntimeError:
            pass
        else:
            raise DesktopError("LEGACY_COMMAND_ENABLED")
        self.page.evaluate("location.href='https://example.invalid/';true")
        need(self.page.evaluate("location.origin===" + json.dumps(f"http://127.0.0.1:{self.port}")), "EXTERNAL_NAVIGATION_ALLOWED")
        return {"isolatedWorld": True, "unknownCommandDenied": True, "externalNavigationDenied": True, "publicStartupOnly": True, "subframeIsolated": True}

    def logout_and_switch(self):
        auth = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
        self.page.evaluate(auth + ".logout()")
        need(self.page.invoke("get_transfer_queue") == {"tasks": []}, "QUEUE_VISIBLE_AFTER_LOGOUT")
        need(not self.page.evaluate("localStorage.getItem('accessToken')"), "TOKEN_RETAINED")
        self.page.invoke("connection:set", {"apiBaseUrl": "http://127.0.0.1:8080/api/v1"})
        return {"logout": True, "nativeQueueHidden": True}

    def close_app(self):
        if not self.process:
            return
        if self.page:
            try:
                self.page.command("Page.close")
            except Exception:
                pass
            self.page.ws.close()
        try:
            self.process.wait(timeout=35)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                # Windows venv Python can have a launcher plus interpreter child.
                subprocess.run(["taskkill", "/PID", str(self.process.pid), "/T", "/F"],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            else:
                self.process.terminate()
            self.process.wait(timeout=10)
            raise DesktopError("APPLICATION_DID_NOT_EXIT")
        need(self.process.returncode == 0, "APPLICATION_EXIT_FAILED")
        self.process = None
        return {"exit": True}

    def cleanup(self):
        if self.engine:
            self.engine.clear_session()
            for task in self.engine.tasks:
                if task["direction"] != "upload":
                    continue
                base = f'/teams/{task["_teamId"]}' if task.get("_teamId") else ""
                for node in task["nodes"]:
                    if node.get("uploadId"):
                        self.api.sessions.append((self.owner, base, node["uploadId"]))
                roots = task["nodes"][1:] if task["nodes"][0].get("_virtual") else task["nodes"][:1]
                for node in roots:
                    if node.get("remoteFileId") and not node.get("_virtual"):
                        self.api.files.append((self.owner, task.get("_teamId"), node["remoteFileId"]))
            self.engine.close()
        failures = self.api.cleanup()
        need(not failures, "CLEANUP_FAILED")
        # Browser storage can contain session credentials; never retain it in reports.
        profile = self.out / "profile"
        if (profile / ".bcd-qt-desktop").is_file():
            need(not profile.is_symlink() and not profile.is_junction()
                 and profile.resolve().is_relative_to(self.out.resolve()), "UNSAFE_PROFILE_PATH")
            shutil.rmtree(profile)

    def run(self):
        failed = False
        try:
            operations = [
                ("isolated-backend-fixtures", self.setup),
                ("64MiB-restart-and-md5", self.transfers),
                ("directory-and-team-batch", self.directories),
                ("team-permission-revocation", self.revoked_permission),
                ("native-expired-token-refresh", self.native_refresh),
            ]
            if not self.native_only:
                operations += [
                ("real-vue-launch", self.launch),
                ("ui-login-reload-and-download", self.login),
                ("ui-native-session-refresh", self.session_refresh),
                ("image-text-preview", self.preview),
                ("pdf-preview", self.pdf),
                ("native-video-and-session-clear", self.native_video),
                ]
            for name, operation in operations:
                self.step(name, operation)
            if self.office_enabled and not self.native_only:
                self.step("onlyoffice-edit-save", self.office)
                self.step("onlyoffice-readonly", self.readonly_office)
            if not self.native_only:
                self.step("bridge-security", self.security)
                self.step("logout-and-native-session", self.logout_and_switch)
                self.step("application-exit", self.close_app)
        except Exception:
            failed = True
        finally:
            if self.process:
                try:
                    self.close_app()
                except Exception:
                    failed = True
            try:
                self.step("run-scoped-cleanup", self.cleanup)
            except Exception:
                failed = True
            report = json.dumps({"runId": self.id, "checks": self.checks, "metrics": self.metrics}, indent=2)
            need(not any(value and len(str(value)) > 8 and str(value) in report for value in self.secrets),
                 "REPORT_PRIVACY_CHECK_FAILED")
            (self.out / "results.json").write_text(report, encoding="utf-8")
            print("report", self.out / "results.json", flush=True)
        return 1 if failed else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base", default="http://127.0.0.1:8080/api/v1")
    parser.add_argument("--office", action="store_true", help="Requires an already running OnlyOffice service")
    parser.add_argument("--native-only", action="store_true", help="Backend acceptance without opening a window")
    parser.add_argument("--port", type=int, default=5194)
    parser.add_argument("--debug-port", type=int, default=9226)
    parser.add_argument("--offscreen", action="store_true", help="Run the real Qt page without a visible desktop window; does not replace native interaction acceptance")
    parser.add_argument("--python-executable", help="Optional interpreter for the isolated application subprocess")
    parser.add_argument("--web-root", type=Path, help="Optional prebuilt desktop web resources for the application subprocess")
    args = parser.parse_args()
    if args.native_only and args.office:
        parser.error("--native-only cannot be combined with --office")
    sys.exit(Validation(args.api_base, args.office, args.native_only, args.port, args.debug_port, args.offscreen,
                        python_executable=args.python_executable, web_root=args.web_root).run())
