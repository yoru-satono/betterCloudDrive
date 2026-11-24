"""Isolated Qt desktop validation host; not a production desktop replacement."""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from PySide6.QtCore import QFile, QIODevice, QObject, Qt, QUrl, Signal, Slot
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineScript, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtWidgets import QApplication, QFileDialog, QMainWindow

from engine import DEFAULTS, Engine, PocError, safe_name


class StaticServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, root, port, fixture_root=None):
        self.root = root.resolve()
        self.fixture_root = fixture_root.resolve() if fixture_root else None
        super().__init__(("127.0.0.1", port), StaticHandler)


class StaticHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            self.send_error(403)
            return
        route = unquote(urlsplit(self.path).path)
        root = self.server.root
        if route.startswith("/__fixtures__/") and self.server.fixture_root:
            root = self.server.fixture_root
            route = route[len("/__fixtures__"):]
        candidate = (root / route.lstrip("/")).resolve()
        if not candidate.is_relative_to(root):
            self.send_error(403)
            return
        if route == "/":
            candidate = self.server.root / "index.html"
        if not candidate.is_file():
            self.send_error(404)
            return
        content = candidate.read_bytes()
        content_type = mimetypes.guess_type(str(candidate))[0] or "application/octet-stream"
        if candidate.suffix in (".js", ".mjs"):
            content_type = "text/javascript"
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass  # URLs can contain tickets. Never write access logs.


class Page(QWebEnginePage):
    def __init__(self, profile, origin, parent):
        super().__init__(profile, parent)
        self.origin = origin

    def acceptNavigationRequest(self, url, navigation_type, main_frame):
        if not main_frame:
            return (url.scheme() in ("http", "https", "about", "blob", "data")
                    or (url.scheme() == "chrome-extension" and url.host() == "mhjfbmdgcfjbbpaeojofohoefgiehjai"))
        return url.toString().split("#", 1)[0] in (self.origin + "/", self.origin + "/index.html", "about:blank")

    def createWindow(self, _):
        return None  # No privileged popup pages.

    def javaScriptConsoleMessage(self, *_):
        pass  # Do not forward browser messages or authentication details to logs.


class Bridge(QObject):
    response = Signal(str)
    event = Signal(str)
    completed = Signal(str, object, str)

    def __init__(self, window, engine, args):
        super().__init__(window)
        self.window, self.engine, self.args = window, engine, args
        self.workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="qt-command")
        self.completed.connect(self.deliver)
        self.handlers = {
            "get_desktop_settings": lambda _: dict(engine.settings),
            "save_desktop_settings": lambda a: engine.save_settings(a["settings"]),
            "get_transfer_queue": lambda _: engine.queue(),
            "restore_desktop_transfers": lambda _: engine.queue(),
            "pause_transfer_node": lambda a: engine.pause(a["nodeId"]),
            "resume_transfer_node": lambda a: engine.resume(a["nodeId"], a["token"], a["apiBaseUrl"]),
            "cancel_transfer_node": lambda a: engine.cancel(a["nodeId"]),
            "clear_finished_transfers": lambda _: engine.clear_finished(),
        }
        if args.debug_port:
            self.handlers["poc:diagnostics"] = lambda _: {"evidence": list(engine.evidence), "active": dict(engine.active)}

    def emit_event(self, name, payload):
        self.event.emit(json.dumps({"name": name, "payload": payload}))

    @Slot(str, object, str)
    def deliver(self, identity, result, error):
        self.response.emit(json.dumps({"id": identity, "result": result, "error": error or None}))

    def background(self, identity, operation):
        def run():
            try:
                self.completed.emit(identity, operation(), "")
            except Exception as error:
                self.completed.emit(identity, None, str(error) if isinstance(error, PocError) else "COMMAND_FAILED")
        self.workers.submit(run)

    @Slot(str)
    def dispatch(self, raw):
        identity = "invalid"
        try:
            request = json.loads(raw)
            identity, command, args = request["id"], request["command"], request.get("args", {})
            if not isinstance(identity, str) or len(identity) > 64 or not isinstance(args, dict):
                raise PocError("INVALID_COMMAND")
            if command in self.handlers:
                self.background(identity, lambda: self.handlers[command](args))
                return
            if command == "window:minimize":
                self.window.showMinimized()
            elif command == "window:maximize":
                self.window.showNormal() if self.window.isMaximized() else self.window.showMaximized()
            elif command == "window:drag":
                self.window.windowHandle().startSystemMove()
            elif command == "window:close":
                self.window.close()
            elif command == "poc:window-state" and self.args.debug_port:
                geometry = self.window.geometry()
                self.deliver(identity, {"maximized": self.window.isMaximized(), "minimized": self.window.isMinimized(),
                             "width": geometry.width(), "height": geometry.height()}, "")
                return
            elif command == "poc:focus" and self.args.debug_port:
                self.window.raise_()
                self.window.activateWindow()
                self.window.centralWidget().setFocus()
            elif command == "app:theme":
                mode = args.get("theme")
                if mode not in (None, "light", "dark"):
                    raise PocError("INVALID_THEME")
                color = {None: Qt.ColorScheme.Unknown, "light": Qt.ColorScheme.Light, "dark": Qt.ColorScheme.Dark}[mode]
                QApplication.styleHints().setColorScheme(color)
            elif command == "choose_default_download_directory":
                folder = QFileDialog.getExistingDirectory(self.window, "Choose PoC download directory")
                self.deliver(identity, folder or None, "")
                return
            elif command == "upload_desktop_files":
                paths, _ = QFileDialog.getOpenFileNames(self.window, "Choose PoC upload files")
                if len(paths) > 1:
                    raise PocError("UNSUPPORTED_IN_POC:multiple_files")
                if not paths:
                    self.deliver(identity, {"selected": False}, "")
                    return
                def upload():
                    task = self.engine.upload(Path(paths[0]), args["request"])
                    return {"selected": True, "batchId": task["id"], "rootName": task["name"]}
                self.background(identity, upload)
                return
            elif command in ("start_desktop_download_file", "download_desktop_file"):
                data = args.get("request", args)
                if command == "download_desktop_file":
                    url = data["url"]
                    base, _, file_id = url.rpartition("/download/")
                    self.engine.checked_base(base)
                    data = {**data, "fileId": int(file_id), "apiBaseUrl": base}
                directory = self.engine.settings["defaultDownloadDir"]
                target = str(Path(directory) / safe_name(data["fileName"])) if directory else QFileDialog.getSaveFileName(self.window, "Save PoC download", safe_name(data["fileName"]))[0]
                if not target:
                    self.deliver(identity, {"saved": False}, "")
                    return
                if Path(target).exists():
                    raise PocError("TARGET_EXISTS")
                def download():
                    self.engine.download(Path(target), data)
                    return {"saved": True, "path": target}
                self.background(identity, download)
                return
            else:
                raise PocError("UNSUPPORTED_IN_POC")
            self.deliver(identity, None, "")
        except Exception as error:
            self.deliver(identity, None, str(error) if isinstance(error, PocError) else "INVALID_COMMAND")


def inject_channel(page):
    resource = QFile(":/qtwebchannel/qwebchannel.js")
    if not resource.open(QIODevice.OpenModeFlag.ReadOnly):
        raise PocError("WEBCHANNEL_RESOURCE_MISSING")
    source = bytes(resource.readAll()).decode("utf-8")
    resource.close()
    source += """
new QWebChannel(qt.webChannelTransport, channel => {
  const bridge = channel.objects.bridge;
  bridge.response.connect(value => window.dispatchEvent(new CustomEvent('bcd:response', {detail:value})));
  bridge.event.connect(value => window.dispatchEvent(new CustomEvent('bcd:event', {detail:value})));
  window.addEventListener('bcd:request', event => {
    if (typeof event.detail === 'string') bridge.dispatch(event.detail);
  });
  window.addEventListener('bcd:hello', () => window.dispatchEvent(new Event('bcd:ready')));
  window.dispatchEvent(new Event('bcd:ready'));
});
"""
    script = QWebEngineScript()
    script.setName("bcd-private-channel")
    script.setSourceCode(source)
    script.setInjectionPoint(QWebEngineScript.InjectionPoint.DocumentCreation)
    script.setWorldId(QWebEngineScript.ScriptWorldId.ApplicationWorld)
    script.setRunsOnSubFrames(False)
    page.scripts().insert(script)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, required=True, help="Dedicated PoC data directory; never point at Tauri data")
    parser.add_argument("--web-root", type=Path)
    parser.add_argument("--api-base", default="http://127.0.0.1:8080/api/v1")
    parser.add_argument("--port", type=int, default=5184)
    parser.add_argument("--debug-port", type=int, help="Opt-in loopback CDP for local validation only")
    parser.add_argument("--fixture-root", type=Path, help="Opt-in public synthetic media fixtures; never a profile directory")
    args = parser.parse_args()
    if args.debug_port:
        os.environ["QTWEBENGINE_REMOTE_DEBUGGING"] = f"127.0.0.1:{args.debug_port}"
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
    web = args.web_root or root / "web"
    if not (web / "index.html").is_file():
        parser.error("Build the PoC web assets first")
    # Refuse existing non-PoC directories, including real Tauri profiles.
    profile = args.profile.resolve()
    marker = profile / ".bcd-qt-poc"
    if profile.exists() and any(profile.iterdir()) and not marker.exists():
        parser.error("Profile directory is not marked as a PoC directory")
    profile.mkdir(parents=True, exist_ok=True)
    marker.touch()
    try:
        server = StaticServer(web, args.port, args.fixture_root)
    except OSError:
        parser.error("PoC loopback port unavailable")
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()
    app = QApplication(sys.argv[:1])
    app.setApplicationName("BetterCloudDrive Qt PoC")
    app.setOrganizationName("BetterCloudDrive Validation")
    window = QMainWindow()
    window.setWindowTitle("BetterCloudDrive — Qt validation")
    window.setWindowFlags(Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
    window.resize(1280, 820)
    window.setMinimumSize(960, 640)
    view = QWebEngineView(window)
    window.setCentralWidget(view)
    browser_profile = QWebEngineProfile("qt-poc", view)
    browser_profile.setPersistentStoragePath(str(profile / "web-storage"))
    browser_profile.setCachePath(str(profile / "cache"))
    browser_profile.setPersistentCookiesPolicy(QWebEngineProfile.PersistentCookiesPolicy.NoPersistentCookies)
    origin = f"http://127.0.0.1:{args.port}"
    page = Page(browser_profile, origin, view)
    page.settings().setAttribute(QWebEngineSettings.WebAttribute.PdfViewerEnabled, True)
    page.settings().setAttribute(QWebEngineSettings.WebAttribute.PluginsEnabled, True)
    page.settings().setAttribute(QWebEngineSettings.WebAttribute.JavascriptCanAccessClipboard, True)
    view.setPage(page)
    engine = Engine(profile / "native", args.api_base)
    bridge = Bridge(window, engine, args)
    engine.emit = bridge.emit_event
    channel = QWebChannel(page)
    channel.registerObject("bridge", bridge)
    page.setWebChannel(channel, QWebEngineScript.ScriptWorldId.ApplicationWorld)
    inject_channel(page)
    view.setUrl(QUrl(origin + "/"))
    window.show()
    result = app.exec()
    bridge.workers.shutdown(wait=True, cancel_futures=True)
    engine.close()
    server.shutdown()
    page.deleteLater()
    app.processEvents()
    return result


if __name__ == "__main__":
    try:
        sys.exit(main())
    except PocError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
