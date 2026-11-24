"""Reproducible Windows live validation; report contains no credentials."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx
import imageio_ffmpeg
import psutil

from cdp import Cdp
from engine import DEFAULTS, Engine, PocError, digest
from live import Api, document, pdf_document, env_values, expired_token, jwt, wait_for

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def need(value, code):
    if not value:
        raise PocError(code)


def memory(pid):
    try:
        parent = psutil.Process(pid)
        return sum(p.memory_info().rss for p in [parent, *parent.children(recursive=True)] if p.is_running())
    except psutil.Error:
        return 0


class Run:
    def __init__(self, args):
        self.args = args
        if args.packaged:
            args.packaged = args.packaged.resolve()
        self.id = uuid.uuid4().hex[:10]
        self.out = REPO / "test-results" / "qt-desktop" / self.id
        self.out.mkdir(parents=True)
        self.results, self.metrics = [], {}
        self.api = Api(self.id)
        self.engine = None
        self.page, self.process = None, None
        self.logs = None
        self.secrets = []
        self.fixtures = self.out / "fixtures"
        self.fixtures.mkdir()

    def step(self, name, function):
        start = time.monotonic()
        try:
            detail = function()
            result = {"name": name, "status": "passed", "detail": detail}
        except Exception as error:
            # Exception messages from third-party HTTP/CDP clients can include
            # complete expressions, URLs, and credentials. Never serialize them.
            import re
            code = str(error) if isinstance(error, PocError) or (isinstance(error, RuntimeError) and re.fullmatch(r"[A-Z_]+", str(error))) else type(error).__name__
            result = {"name": name, "status": "failed", "error": code}
            if self.page:
                try:
                    self.page.screenshot(self.out / ("failed-" + name + ".png"))
                except Exception:
                    result["screenshot"] = "unavailable"
        result["seconds"] = round(time.monotonic() - start, 3)
        self.results.append(result)
        self.save()
        print(name + ": " + result["status"], flush=True)
        return result["status"] == "passed"

    def save(self):
        result = {"runId": self.id, "platform": sys.platform, "python": sys.version.split()[0],
                  "mode": "packaged" if self.args.packaged else "source", "checks": self.results, "metrics": self.metrics}
        text = json.dumps(result, ensure_ascii=False, indent=2)
        if any(secret and secret in text for secret in self.secrets):
            raise PocError("REPORT_PRIVACY_CHECK_FAILED")
        (self.out / "results.json").write_text(text, encoding="utf-8")

    def setup(self):
        # Actuator is deliberately on the container-only management port 8081.
        health = httpx.get(self.api.base + "/auth/me", timeout=5, trust_env=False)
        # Spring Security's anonymous entry point currently returns 403.
        need(health.status_code in (401, 403), "BACKEND_NOT_REACHABLE")
        self.owner = self.api.user("owner")
        self.viewer = self.api.user("viewer")
        for user in self.api.users:
            self.secrets.extend(user.get(key, "") for key in ("password", "accessToken", "refreshToken"))
        team = self.api.call("POST", "/teams", self.owner, json={"name": "qt_poc_" + self.id})
        self.team = team["id"]
        self.api.teams.append((self.owner, self.team))
        self.api.call("POST", f"/teams/{self.team}/members", self.owner, json={"username": self.viewer["username"], "role": "VIEWER"})
        self.engine = Engine(self.out / "engine")
        return {"backend": "healthy", "isolatedAccounts": 2}

    def upload_task(self, path, team=None, user=None):
        user = user or self.owner
        base = self.api.base + (f"/teams/{team}" if team else "")
        return self.engine.upload(path, {"apiBaseUrl": base, "token": user["accessToken"], "parentId": None})

    def finished(self, task, timeout=120):
        wait_for(lambda: task["status"] in ("done", "instant", "error", "canceled"), timeout)
        need(task["status"] in ("done", "instant"), "TRANSFER_" + str(task["nodes"][0].get("error")))
        node = task["nodes"][0]
        if task["direction"] == "upload":
            entry = (self.owner, self.team if "/teams/" in task["apiBaseUrl"] else None, node["remoteFileId"])
            if entry not in self.api.files:
                self.api.files.append(entry)
        return node

    def transfers(self):
        big = self.fixtures / ("qt_" + self.id + "_64MiB.bin")
        with big.open("wb") as stream:
            block = secrets.token_bytes(1024 * 1024)
            for _ in range(64):
                stream.write(block)
        self.big_path = big
        self.engine.save_settings({**DEFAULTS, "uploadLimitBytesPerSec": 4 * 1024 * 1024})
        item = self.upload_task(big)
        wait_for(lambda: item["nodes"][0]["completedChunks"] >= 1 or item["status"] == "error")
        need(item["status"] != "error", "INITIAL_UPLOAD_FAILED")
        self.engine.pause(item["id"])
        self.engine.futures[item["id"]].result(timeout=35)
        self.engine.close()
        self.engine = Engine(self.out / "engine")
        restored = self.engine._find(item["id"])
        self.engine.save_settings(DEFAULTS)
        self.engine.resume(restored["id"], self.owner["accessToken"], self.api.base)
        node = self.finished(restored)
        evidence = self.engine.evidence[-1]
        need(0 < evidence["missingCount"] < evidence["totalChunks"], "UPLOAD_DID_NOT_RESUME_MISSING_PARTS")
        self.big_file = self.api.call("GET", "/files/" + str(node["remoteFileId"]), self.owner)
        need(self.big_file["md5Hash"] == digest(big), "UPLOAD_CHECKSUM_MISMATCH")
        target = self.out / "downloads" / big.name
        self.engine.save_settings({**DEFAULTS, "downloadLimitBytesPerSec": 4 * 1024 * 1024})
        request = {"fileId": node["remoteFileId"], "fileName": big.name, "apiBaseUrl": self.api.base, "token": self.owner["accessToken"]}
        download = self.engine.download(target, request)
        wait_for(lambda: download["nodes"][0]["bytesDone"] >= 1024 * 1024 or download["status"] == "error")
        need(download["status"] != "error", "INITIAL_DOWNLOAD_FAILED")
        self.engine.pause(download["id"])
        self.engine.futures[download["id"]].result(timeout=35)
        self.engine.close()
        self.engine = Engine(self.out / "engine")
        download = self.engine._find(download["id"])
        self.engine.save_settings(DEFAULTS)
        self.engine.resume(download["id"], self.owner["accessToken"], self.api.base)
        self.finished(download)
        ranges = [entry for entry in self.engine.evidence if entry["kind"] == "download-range"]
        need(any(entry["start"] > 0 and entry["status"] == 206 for entry in ranges), "DOWNLOAD_DID_NOT_RESUME_RANGE")
        need(digest(target) == digest(big), "DOWNLOAD_CHECKSUM_MISMATCH")
        self.metrics["transferEvidence"] = {"upload": evidence, "download": ranges}
        return {"bytes": big.stat().st_size, "uploadMissingPartsResumed": True, "downloadNonzeroRangeResumed": True, "md5Matched": True}

    def small_files(self):
        result = []
        for name, body in (("空文件_" + self.id + ".txt", b""), ("中文_" + self.id + ".txt", "Qt 文本预览".encode())):
            path = self.fixtures / name
            path.write_bytes(body)
            item = self.upload_task(path)
            result.append(self.finished(item)["remoteFileId"])
        duplicate = self.fixtures / ("秒传_" + self.id + ".txt")
        duplicate.write_text("Qt 文本预览", encoding="utf-8")
        instant = self.upload_task(duplicate)
        self.finished(instant)
        need(instant["status"] == "instant", "INSTANT_UPLOAD_NOT_USED")
        team_path = self.fixtures / ("团队_" + self.id + ".txt")
        team_path.write_bytes(secrets.token_bytes(4096))
        self.team_file = self.finished(self.upload_task(team_path, self.team))
        return {"emptyAndUnicode": True, "instant": True, "teamUpload": True}

    def revoked_permission(self):
        base = self.api.base + f"/teams/{self.team}"
        target = self.out / "downloads" / "revoked.txt"
        self.engine.save_settings({**DEFAULTS, "downloadLimitBytesPerSec": 128})
        item = self.engine.download(target, {"apiBaseUrl": base, "token": self.viewer["accessToken"],
                                    "fileId": self.team_file["remoteFileId"], "fileName": "revoked.txt"})
        wait_for(lambda: item["nodes"][0]["bytesDone"] > 0)
        self.engine.pause(item["id"])
        self.engine.futures[item["id"]].result(timeout=35)
        self.api.call("DELETE", f"/teams/{self.team}/members/{self.viewer['id']}", self.owner)
        self.engine.save_settings(DEFAULTS)
        try:
            self.engine.resume(item["id"], self.viewer["accessToken"], base)
        except PocError as error:
            need(str(error) == "PERMISSION_DENIED", "UNEXPECTED_PERMISSION_RESULT")
        else:
            wait_for(lambda: item["status"] == "error")
            need(item["nodes"][0]["error"] == "PERMISSION_DENIED", "PERMISSION_NOT_TERMINAL")
        need(not target.exists(), "REVOKED_FILE_WAS_PUBLISHED")
        self.api.call("POST", f"/teams/{self.team}/members", self.owner, json={"username": self.viewer["username"], "role": "VIEWER"})
        return {"permanent403": True, "published": False}

    def prepare_media(self):
        executable = imageio_ffmpeg.get_ffmpeg_exe()
        for name, codecs in (("sample.webm", ["-c:v", "libvpx-vp9", "-c:a", "libopus"]),
                             ("sample.mp4", ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart"])):
            subprocess.run([executable, "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=12",
                            "-f", "lavfi", "-i", "sine=frequency=440", "-t", "3", *codecs,
                            str(self.fixtures / name)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return {"generated": ["vp9-opus", "h264-aac"], "seconds": 3}

    def legacy(self):
        import copy
        from engine import atomic_json
        root = self.out / "legacy-synthetic"
        sample = copy.deepcopy(self.engine.tasks[0])
        sample.pop("_account", None)
        sample["status"] = "transferring"
        atomic_json(root / "settings.json", {**DEFAULTS, "maxConcurrentUploads": 2})
        atomic_json(root / "transfers.json", {"tasks": [sample]})
        part = root / "fixture.part"
        part.write_bytes(b"synthetic partial bytes")
        original = digest(part)
        imported = Engine(root)
        try:
            need(imported.settings["maxConcurrentUploads"] == 2, "LEGACY_SETTINGS_NOT_READ")
            need(imported.tasks[0]["status"] == "paused", "LEGACY_TASK_NOT_PAUSED")
            try:
                imported.resume(sample["id"], self.owner["accessToken"], self.api.base)
            except PocError as error:
                need(str(error) == "LEGACY_ACCOUNT_REBIND_REQUIRED", "LEGACY_REBIND_NOT_ENFORCED")
            else:
                raise PocError("LEGACY_AUTO_RESUMED_WITHOUT_ACCOUNT")
        finally:
            imported.close()
        need(digest(part) == original, "LEGACY_PART_CHANGED")
        return {"settingsReadable": True, "queueReadable": True, "partPreserved": True,
                "automaticResume": False, "requiresAccountRebind": True, "usesSyntheticDataOnly": True}

    def window_controls(self):
        p = self.page
        p.invoke("window:maximize")
        need(p.invoke("poc:window-state")["maximized"], "MAXIMIZE_FAILED")
        p.invoke("window:maximize")
        need(not p.invoke("poc:window-state")["maximized"], "RESTORE_FAILED")
        p.invoke("window:minimize")
        need(p.invoke("poc:window-state")["minimized"], "MINIMIZE_FAILED")
        p.invoke("window:maximize")
        p.invoke("window:maximize")
        for theme in ("light", "dark", None):
            p.invoke("app:theme", {"theme": theme})
        return {"maximizeRestoreMinimize": True, "nativeThemeCommands": True}

    def launch(self):
        self.logs = (self.out / "qt-process.log").open("w", encoding="utf-8")
        binary = self.args.packaged or sys.executable
        command = [str(binary)] + ([] if self.args.packaged else [str(HERE / "app.py")])
        command += ["--profile", str(self.out / "profile"), "--debug-port", "9224", "--fixture-root", str(self.fixtures)]
        env = {**os.environ, "PYTHONUTF8": "1"}
        if self.args.packaged:
            env["PATH"] = str(Path(os.environ["SystemRoot"]) / "System32")
            for key in ("PYTHONHOME", "PYTHONPATH", "QT_PLUGIN_PATH", "QML2_IMPORT_PATH", "QTWEBENGINEPROCESS_PATH"):
                env.pop(key, None)
        start = time.monotonic()
        self.process = subprocess.Popen(command, cwd=self.out, env=env, stdout=self.logs, stderr=self.logs)
        self.page = Cdp()
        self.page.wait("document.querySelector('.login-page__form input') && window.__BCD_QT_POC__")
        self.page.invoke("get_desktop_settings")
        self.metrics["startupSeconds"] = round(time.monotonic() - start, 3)
        self.metrics["idleProcessTreeRssBytes"] = memory(self.process.pid)
        return {"realVue": True, "bridgeRoundTrip": True}

    def login(self):
        p = self.page
        for selector, text in ((".login-page__form input[type=text]", self.owner["username"]), (".login-page__form input[type=password]", self.owner["password"])):
            p.click(selector)
            p.command("Input.insertText", {"text": text})
        p.click("form button[type=submit]")
        p.wait("location.hash.includes('/files') && document.querySelector('[data-testid^=file-card]')", 40)
        p.screenshot(self.out / "files.png")
        p.command("Page.reload")
        p.wait("location.hash.includes('/files') && document.querySelector('[data-testid^=file-card]')", 40)
        if not hasattr(self, "big_file"):
            return {"uiLogin": True, "reloadSession": True}
        settings = p.invoke("get_desktop_settings")
        p.invoke("save_desktop_settings", {"settings": {**settings, "defaultDownloadDir": str(self.out / "bridge-downloads")}})
        p.evaluate("window.qtEvents=0; window.__BCD_QT_POC__.listen('transfer:queue-updated',()=>window.qtEvents++).then(x=>window.qtUnlisten=x)")
        p.invoke("start_desktop_download_file", {"request": {"fileId": self.big_file["id"], "fileName": "bridge.bin", "apiBaseUrl": self.api.base, "token": self.owner["accessToken"]}})
        p.wait("window.qtEvents>1", 30)
        wait_for(lambda: (self.out / "bridge-downloads" / "bridge.bin").exists(), 120)
        need(digest(self.out / "bridge-downloads" / "bridge.bin") == digest(self.big_path), "BRIDGE_DOWNLOAD_CHECKSUM")
        p.evaluate("window.qtUnlisten(); true")
        self.metrics["transferProcessTreeRssBytes"] = memory(self.process.pid)
        return {"uiLogin": True, "reloadSession": True, "nativeDownload64MiB": True, "eventDelivery": True}

    def refresh(self):
        old = expired_token(self.owner["accessToken"], env_values(REPO))
        self.secrets.append(old)
        self.page.evaluate("localStorage.setItem('accessToken'," + json.dumps(old) + ");true")
        self.page.command("Page.reload")
        self.page.wait("location.hash.includes('/files') && document.querySelector('[data-testid^=file-card]')", 40)
        token = self.page.evaluate("localStorage.getItem('accessToken')")
        need(token != old and bool(token), "UI_TOKEN_NOT_REFRESHED")
        # Keep fixture cleanup credentials synchronized with refresh rotation.
        self.owner["accessToken"] = token
        self.owner["refreshToken"] = self.page.evaluate("localStorage.getItem('refreshToken')")
        self.secrets.extend((token, self.owner["refreshToken"]))
        return {"expiredTokenRecovered": True}

    def media(self, name):
        p = self.page
        source = f"http://127.0.0.1:5184/__fixtures__/{name}"
        result = p.evaluate("""new Promise(resolve=>{
          const video=document.createElement('video'); video.controls=true;
          video.style.cssText='position:fixed;z-index:99999;top:80px;left:80px;width:640px;background:black';
          document.body.append(video); video.src=""" + json.dumps(source) + """;
          let done=false; const finish=x=>{if(done)return;done=true;clearTimeout(timer);video.pause();video.remove();resolve(x)};
          const timer=setTimeout(()=>finish({ok:false,reason:'MEDIA_TIMEOUT'}),15000);
          video.onerror=()=>finish({ok:false,reason:'MEDIA_ERROR',code:video.error?.code});
          video.onloadeddata=()=>video.play().catch(()=>finish({ok:false,reason:'PLAY_FAILED'}));
          video.ontimeupdate=()=>{if(video.currentTime>0.3){video.ontimeupdate=null;
            video.currentTime=1.5; video.onseeked=()=>finish({ok:true,width:video.videoWidth,
            decoded:video.getVideoPlaybackQuality().totalVideoFrames,
            audioDecoded:video.webkitAudioDecodedByteCount||0,seeked:video.currentTime});}};
        })""")
        self.metrics["media-" + name] = result
        need(result and result.get("ok") and result.get("decoded", 0) > 0, "MEDIA_DECODE_FAILED")
        need(result.get("audioDecoded", 0) > 0, "MEDIA_AUDIO_NOT_DECODED")
        return result

    def preview(self):
        png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nS8AAAAASUVORK5CYII=")
        image = self.api.upload(self.owner, "image_" + self.id + ".png", png)
        text = self.api.upload(self.owner, "preview_" + self.id + ".txt", b"Qt preview fixture")
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        for file in (image, text):
            self.page.evaluate(store + ".openPreview(" + json.dumps(file) + ")")
            selector = "[role=dialog] img" if file is image else "[role=dialog] iframe"
            self.page.wait("!!document.querySelector(" + json.dumps(selector) + ")", 20)
            if file is image:
                self.page.wait("document.querySelector('[role=dialog] img').naturalWidth>0")
            else:
                self.page.wait("document.querySelector('[role=dialog] iframe').contentDocument?.body.innerText.includes('Qt preview fixture')")
            self.page.evaluate(store + ".closePreview()")
        return {"image": True, "text": True}

    def office(self):
        file = self.api.upload(self.owner, "office_" + self.id + ".docx", document(), self.team)
        self.office_file = file
        before = file["versionCount"]
        config = self.api.call("GET", f"/teams/{self.team}/office/files/{file['id']}/config", self.owner)
        need(config["editable"], "OFFICE_NOT_EDITABLE")
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        script_url = config["documentServerUrl"].rstrip("/") + "/web-apps/apps/api/documents/api.js"
        self.page.evaluate("""new Promise((resolve,reject)=>{
          const script=document.createElement('script'); script.src=""" + json.dumps(script_url) + """;
          script.dataset.onlyofficeApi=script.src;
          script.onload=()=>{script.dataset.loaded='true';
            const Original=window.DocsAPI.DocEditor;
            window.DocsAPI.DocEditor=function(id,config){
              const ready=config.events?.onDocumentReady;
              config.events={...config.events,onDocumentReady:(event)=>{window.qtOfficeReady=true;ready?.(event)},
                onDocumentStateChange:event=>{window.qtOfficeModified=!!event.data;window.qtOfficeWasModified=window.qtOfficeWasModified||!!event.data},
                onError:event=>{window.qtOfficeError=event.data?.errorCode}};
              return new Original(id,config);
            }; resolve(true);
          };script.onerror=()=>reject(new Error('OFFICE_SCRIPT_FAILED'));document.head.append(script);
        })""")
        self.page.evaluate(store + ".openPreview(" + json.dumps(file) + "," + json.dumps({"teamId": self.team}) + ")")
        self.page.wait("!!document.querySelector('[data-testid=onlyoffice-shell] iframe')", 40)
        self.page.wait("window.qtOfficeReady===true", 90)
        # Enter the actual document iframe through CDP's execution context.
        self.page.command("Page.enable")
        tree = self.page.command("Page.getFrameTree")["frameTree"]
        frames = []
        def walk(item):
            frames.append(item["frame"])
            for child in item.get("childFrames", []):
                walk(child)
        walk(tree)
        frame = next((f for f in frames if "/web-apps/apps/documenteditor/" in f["url"]), None)
        need(frame is not None, "OFFICE_FRAME_NOT_LOADED")
        # Wait for the editor to advertise its loaded state, rather than assuming
        # an iframe alone proves document rendering or a save callback.
        context = self.page.command("Page.createIsolatedWorld", {"frameId": frame["id"], "worldName": "qt-validation"})["executionContextId"]
        def editor_ready():
            value = self.page.command("Runtime.evaluate", {"contextId": context,
                "expression": "!!document.querySelector('#editor_sdk canvas')", "returnByValue": True})
            return value.get("result", {}).get("value")
        wait_for(editor_ready, 60)
        self.page.command("Runtime.evaluate", {"contextId": context, "expression": "Array.from(document.querySelectorAll('button,[role=button]')).filter(e=>/^(Got it|知道了|OK)$/i.test(e.innerText.trim())).forEach(e=>e.click())"})
        self.page.invoke("poc:focus")
        self.page.screenshot(self.out / "office.png")
        value = self.page.evaluate("(() => {const r=document.querySelector('[data-testid=onlyoffice-shell] iframe').getBoundingClientRect();return {x:r.x+r.width*.5,y:r.y+r.height*.5}})()")
        for kind in ("mousePressed", "mouseReleased"):
            self.page.command("Input.dispatchMouseEvent", {"type": kind, "button": "left", "clickCount": 1, **value})
        marker = "QT_SAVE_" + self.id
        self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": "End", "code": "End", "modifiers": 2, "windowsVirtualKeyCode": 35})
        self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": "End", "code": "End", "modifiers": 2, "windowsVirtualKeyCode": 35})
        for char in marker:
            self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": char, "text": char, "unmodifiedText": char})
            self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": char})
        self.page.wait("window.qtOfficeWasModified===true", 15)
        self.page.screenshot(self.out / "office-edited.png")
        self.page.command("Input.dispatchKeyEvent", {"type": "keyDown", "key": "s", "code": "KeyS", "modifiers": 2, "windowsVirtualKeyCode": 83})
        self.page.command("Input.dispatchKeyEvent", {"type": "keyUp", "key": "s", "code": "KeyS", "modifiers": 2, "windowsVirtualKeyCode": 83})
        self.page.wait("window.qtOfficeWasModified===true && window.qtOfficeModified===false", 20)
        key = config["config"]["document"]["key"]
        payload = {"c": "forcesave", "key": key}
        secret = env_values(REPO)["ONLYOFFICE_JWT_SECRET"].encode()
        payload["token"] = jwt(payload, secret)
        response = self.api.client.post("http://127.0.0.1:8082/coauthoring/CommandService.ashx", json=payload).json()
        self.metrics["officeForceSaveCode"] = response.get("error")
        need(response.get("error") in (0, 4), "OFFICE_FORCE_SAVE_" + str(response.get("error")))
        def saved():
            current = self.api.call("GET", f"/teams/{self.team}/files/{file['id']}", self.owner)
            if current.get("versionCount", 0) <= before:
                return False
            download = self.api.client.get(self.api.base + f"/teams/{self.team}/download/{file['id']}", headers={"Authorization": "Bearer " + self.owner["accessToken"]})
            import io, zipfile, xml.etree.ElementTree as ET
            with zipfile.ZipFile(io.BytesIO(download.content)) as archive:
                text = "".join(ET.fromstring(archive.read("word/document.xml")).itertext())
                return marker in text
        wait_for(saved, 90, interval=3)
        self.page.evaluate(store + ".closePreview()")
        return {"editorRendered": True, "realCallback": True, "versionCreated": True, "markerSaved": True,
                "forceSaveResponse": response.get("error")}

    def readonly_office(self):
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        personal = self.api.upload(self.owner, "personal_" + self.id + ".docx", document())
        config = self.api.call("GET", f"/office/files/{personal['id']}/config", self.owner)
        need(not config["editable"] and config["config"]["editorConfig"]["mode"] == "view", "PERSONAL_OFFICE_NOT_READONLY")
        self.page.evaluate("window.qtOfficeReady=false;" + store + ".openPreview(" + json.dumps(personal) + ")")
        self.page.wait("window.qtOfficeReady===true", 60)
        self.page.screenshot(self.out / "personal-office-readonly.png")
        self.page.evaluate(store + ".closePreview()")
        file = self.office_file
        config = self.api.call("GET", f"/teams/{self.team}/office/files/{file['id']}/config", self.viewer)
        need(not config["editable"] and config["config"]["editorConfig"]["mode"] == "view", "TEAM_VIEWER_NOT_READONLY")
        auth = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
        self.page.evaluate(auth + ".login(" + json.dumps(self.viewer["username"]) + "," + json.dumps(self.viewer["password"]) + ")")
        self.page.evaluate("window.qtOfficeReady=false;" + store + ".openPreview(" + json.dumps(file) + "," + json.dumps({"teamId": self.team}) + ")")
        self.page.wait("window.qtOfficeReady===true", 60)
        self.page.screenshot(self.out / "team-office-readonly.png")
        self.page.evaluate(store + ".closePreview()")
        self.page.evaluate(auth + ".login(" + json.dumps(self.owner["username"]) + "," + json.dumps(self.owner["password"]) + ")")
        for key in ("accessToken", "refreshToken"):
            self.owner[key] = self.page.evaluate("localStorage.getItem(" + json.dumps(key) + ")")
            self.secrets.append(self.owner[key])
        return {"personalReadOnlyRendered": True, "teamViewerReadOnlyRendered": True}

    def pdf(self):
        from rendering import pdf_page_has_ink
        file = self.api.upload(self.owner, "preview_" + self.id + ".pdf", pdf_document())
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('preview')"
        self.page.evaluate(store + ".openPreview(" + json.dumps(file) + ")")
        self.page.wait("!!document.querySelector('[role=dialog] iframe')", 20)
        previous_capture = None
        def rendered():
            nonlocal previous_capture
            tree = self.page.command("Page.getFrameTree")["frameTree"]
            frames = []
            def walk(item):
                frames.append(item["frame"])
                for child in item.get("childFrames", []):
                    walk(child)
            walk(tree)
            frame = next((f for f in frames if f.get("mimeType") == "application/pdf"), None)
            if not frame:
                return False
            rect = self.page.evaluate("(() => {const r=document.querySelector('[role=dialog] iframe').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height,scale:1}})()")
            capture = self.page.command("Page.captureScreenshot", {"format": "png", "clip": rect})
            png = base64.b64decode(capture["data"])
            checksum = hashlib.sha256(png).hexdigest()
            stable = checksum == previous_capture
            previous_capture = checksum
            if stable and pdf_page_has_ink(png):
                (self.out / "pdf-content.png").write_bytes(png)
                return True
            return False
        wait_for(rendered, 20, interval=1)
        self.page.screenshot(self.out / "pdf.png")
        self.page.evaluate(store + ".closePreview()")
        # Chromium's PDF plugin does not expose page text through this page's
        # CDP AX tree. Keep the visual assertion explicit instead of claiming it.
        return {"realBackendPdf": True, "embeddedPdfFrameLoaded": True, "visiblePageAndInk": True,
                "screenshotForVisualReview": "pdf-content.png", "stableConsecutiveFrames": True}

    def security(self):
        p = self.page
        need(p.evaluate("typeof qt === 'undefined'"), "TRANSPORT_EXPOSED_IN_MAIN_WORLD")
        expression = """new Promise(resolve=>{
          const frame=document.createElement('iframe'); frame.src='http://127.0.0.1:3000';
          document.body.append(frame); frame.onload=()=>{let blocked=false;try{void frame.contentWindow.__BCD_QT_POC__; }catch{blocked=true;} frame.remove();resolve(blocked)};
        })"""
        need(p.evaluate(expression), "EXTERNAL_FRAME_PARENT_ACCESS")
        caught = p.evaluate("window.__BCD_QT_POC__.invoke('run_arbitrary_python').then(()=>false,e=>e.message==='UNSUPPORTED_IN_POC')")
        need(caught, "UNKNOWN_COMMAND_NOT_REJECTED")
        # Popups do not receive a page, and the top-level external navigation is refused.
        p.evaluate("location.href='http://127.0.0.1:3000';true")
        p.wait("location.origin==='http://127.0.0.1:5184'")
        return {"isolatedWorld": True, "crossOriginFrameBlocked": True, "externalNavigationBlocked": True, "commandAllowlist": True}

    def logout(self):
        store = "document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('auth')"
        self.page.evaluate(store + ".logout()")
        need(self.page.evaluate("!localStorage.getItem('accessToken') && !localStorage.getItem('refreshToken')"), "LOGOUT_NOT_CLEARED")
        self.page.evaluate("localStorage.clear(); sessionStorage.clear(); true")
        return {"credentialsCleared": True}

    def close_app(self):
        tracked = []
        if self.process and self.process.poll() is None:
            tracked = psutil.Process(self.process.pid).children(recursive=True)
        if self.page:
            try:
                self.metrics["nativeDiagnostics"] = self.page.invoke("poc:diagnostics")
                self.page.evaluate("localStorage.clear();sessionStorage.clear();window.__BCD_QT_POC__.invoke('window:close');true")
            except Exception:
                pass
            self.page.close()
        if self.process:
            try:
                self.process.wait(timeout=35)
            except subprocess.TimeoutExpired:
                parent = psutil.Process(self.process.pid)
                for child in parent.children(recursive=True):
                    child.kill()
                parent.kill()
                self.results.append({"name": "process-exit", "status": "failed", "error": "EXIT_TIMEOUT"})
            _, alive = psutil.wait_procs(tracked, timeout=5)
            self.results.append({"name": "webengine-subprocess-exit", "status": "failed" if alive else "passed", "remaining": len(alive)})
            for child in alive:
                child.kill()
        if self.logs:
            self.logs.close()
        # LevelDB can retain deleted localStorage values in old log records.
        # Erase only this run's dedicated browser data after its process exits.
        import shutil
        for name in ("web-storage", "cache"):
            target = (self.out / "profile" / name).resolve()
            need(target.is_relative_to(self.out.resolve()), "UNSAFE_PROFILE_CLEANUP")
            if target.exists():
                shutil.rmtree(target)

    def execute(self):
        try:
            ready = self.step("backend-and-accounts", self.setup)
            if ready:
                if self.args.previews_only:
                    self.api.upload(self.owner, "seed_" + self.id + ".txt", b"Preview seed")
                    if self.step("qt-real-vue-and-bridge", self.launch):
                        self.step("ui-login", self.login)
                        self.step("image-text-preview", self.preview)
                        self.step("pdf-preview", self.pdf)
                        self.step("logout", self.logout)
                    return int(any(item["status"] == "failed" for item in self.results))
                if self.args.office_only:
                    self.api.upload(self.owner, "seed_" + self.id + ".docx", document())
                    if self.step("qt-real-vue-and-bridge", self.launch):
                        self.step("ui-login", self.login)
                        self.step("onlyoffice-edit-save", self.office)
                        self.step("logout", self.logout)
                    return int(any(item["status"] == "failed" for item in self.results))
                self.step("transfer-64MiB-restart-and-md5", self.transfers)
                if self.step("empty-unicode-instant-team-upload", self.small_files):
                    self.step("team-permission-revocation", self.revoked_permission)
                self.step("media-fixtures", self.prepare_media)
                self.step("synthetic-legacy-compatibility", self.legacy)
                if self.step("qt-real-vue-and-bridge", self.launch):
                    self.step("window-controls-and-theme", self.window_controls)
                    if hasattr(self, "big_file"):
                        self.step("ui-login-reload-native-download", self.login)
                        self.step("expired-token-ui-recovery", self.refresh)
                    self.step("image-text-preview", self.preview)
                    self.step("pdf-preview", self.pdf)
                    self.step("webm-play-seek-audio", lambda: self.media("sample.webm"))
                    self.step("h264-aac-play-seek-audio", lambda: self.media("sample.mp4"))
                    self.step("onlyoffice-edit-save", self.office)
                    self.step("personal-and-team-readonly-office", self.readonly_office)
                    self.step("bridge-security-boundaries", self.security)
                    self.step("logout", self.logout)
        finally:
            self.close_app()
            if self.engine:
                # Include completed engine tasks even if the live test failed
                # between backend completion and the assertion.
                for task in self.engine.tasks:
                    node = task["nodes"][0]
                    if task["direction"] == "upload" and node.get("remoteFileId"):
                        entry = (self.owner, self.team if "/teams/" in task["apiBaseUrl"] else None, node["remoteFileId"])
                        if entry not in self.api.files:
                            self.api.files.append(entry)
                    elif task["direction"] == "upload" and node.get("uploadId"):
                        base = task["apiBaseUrl"][len(self.api.base):]
                        self.api.sessions.append((self.owner, base, node["uploadId"]))
                self.engine.close()
                self.metrics["engineEvidence"] = self.engine.evidence
            failures = self.api.cleanup()
            self.results.append({"name": "run-scoped-cleanup", "status": "failed" if failures else "passed", "errors": failures,
                                 "note": "Cleanup is limited to run-owned IDs; test accounts are retained for audit. See errors for incomplete cleanup."})
            self.save()
        print("Results: " + str(self.out / "results.json"), flush=True)
        return int(any(item["status"] == "failed" for item in self.results))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--packaged", type=Path)
    parser.add_argument("--office-only", action="store_true")
    parser.add_argument("--previews-only", action="store_true")
    sys.exit(Run(parser.parse_args()).execute())
