"""BetterCloudDrive Qt desktop host."""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from PySide6.QtCore import QFile, QIODevice, QObject, Qt, QUrl, Signal, Slot, QLockFile, qInstallMessageHandler, QEvent, QRectF
from PySide6.QtWebChannel import QWebChannel
from PySide6.QtWebEngineCore import QWebEnginePage, QWebEngineProfile, QWebEngineScript, QWebEngineSettings
from PySide6.QtWebEngineWidgets import QWebEngineView
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QApplication, QFileDialog, QMainWindow, QWidget, QMessageBox

from engine import Engine, safe_name
from network import DesktopError as PocError, api_url
from media import MediaGateway, PlayerWindow


class StaticServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, root, port):
        self.root = root.resolve()
        super().__init__(("127.0.0.1", port), StaticHandler)


class StaticHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Host") != f"127.0.0.1:{self.server.server_port}":
            self.send_error(403)
            return
        route = unquote(urlsplit(self.path).path)
        root = self.server.root
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


class DesktopView(QWebEngineView):
    """Track titlebar dragging natively, without a WebEngine IPC round-trip."""
    def __init__(self, parent):
        super().__init__(parent)
        self.drag_regions = []
        self.drag_anchor = None
        self.drag_origin = None
        self.drag_started = False
        self.drag_presses = 0
        QApplication.instance().installEventFilter(self)

    def reset_drag(self):
        self.drag_anchor = self.drag_origin = None
        self.drag_started = False

    def move_drag(self, point):
        delta = point - self.drag_anchor
        if not self.drag_started and delta.manhattanLength() < max(1, QApplication.startDragDistance()):
            return
        self.drag_started = True
        window = self.window()
        if window.isMaximized() or window.isFullScreen():
            return
        target = self.drag_origin + delta
        if target != window.pos():
            window.move(target)

    def eventFilter(self, watched, event):
        kind = event.type()
        if watched in (self, self.window()) and kind in (
                QEvent.Type.WindowDeactivate, QEvent.Type.WindowStateChange,
                QEvent.Type.Hide, QEvent.Type.Close):
            self.reset_drag()
        inside = isinstance(watched, QWidget) and (watched is self or self.isAncestorOf(watched))
        if kind == QEvent.Type.MouseButtonPress:
            self.reset_drag()
            if inside and event.button() == Qt.MouseButton.LeftButton:
                point = self.mapFromGlobal(event.globalPosition().toPoint())
                if any(rect.contains(point) for rect in self.drag_regions):
                    self.drag_presses += 1
                    if not self.window().isMaximized() and not self.window().isFullScreen():
                        self.drag_anchor = event.globalPosition().toPoint()
                        self.drag_origin = self.window().pos()
                    return True
        elif self.drag_anchor is not None and kind == QEvent.Type.MouseMove:
            if not event.buttons() & Qt.MouseButton.LeftButton:
                self.reset_drag()
            elif inside:
                self.move_drag(event.globalPosition().toPoint())
                return True
        elif self.drag_anchor is not None and kind == QEvent.Type.MouseButtonRelease:
            handled = inside and event.button() == Qt.MouseButton.LeftButton
            if handled:
                self.move_drag(event.globalPosition().toPoint())
            self.reset_drag()
            if handled:
                return True
        return super().eventFilter(watched, event)


class Page(QWebEnginePage):
    def __init__(self, profile, origin, parent):
        super().__init__(profile, parent)
        self.origin = origin

    def acceptNavigationRequest(self, url, navigation_type, main_frame):
        if not main_frame:
            return (url.scheme() in ("http", "https", "about", "blob", "data")
                    or (url.scheme() == "chrome-extension" and url.host() == "mhjfbmdgcfjbbpaeojofohoefgiehjai"))
        return url.toString().split("#", 1)[0] in (self.origin + "/", self.origin + "/index.html")

    def createWindow(self, _):
        return None  # No privileged popup pages.

    def javaScriptConsoleMessage(self, *_):
        pass  # Do not forward browser messages or authentication details to logs.


class DesktopWindow(QMainWindow):
    revoke = None

    def closeEvent(self, event):
        if self.revoke:
            self.revoke()
        super().closeEvent(event)


class Bridge(QObject):
    response = Signal(str)
    event = Signal(str)
    completed = Signal(str, object, str)
    native_event = Signal(str, object)
    media_ready = Signal(str, str, str, str)
    media_failed = Signal(str, str)

    def __init__(self, window, engine, args):
        super().__init__(window)
        self.window, self.engine, self.args = window, engine, args
        self.workers = ThreadPoolExecutor(max_workers=4, thread_name_prefix="desktop-command")
        self.completed.connect(self.deliver)
        self.native_event.connect(self.receive_event)
        self.media_ready.connect(self.show_player)
        self.gateway = MediaGateway(engine)
        self.gateway.failed = self.media_failed.emit
        self.media_failed.connect(self.player_failure)
        self.players = []
        self.media_intent = 0
        self.media_requests = {}
        self.media_current = None
        self.command_contexts = {}
        self.handlers = {
            "get_desktop_settings": lambda _: dict(engine.settings),
            "save_desktop_settings": lambda a: engine.save_settings(a["settings"]),
            "get_transfer_queue": lambda _: engine.queue(),
            "restore_desktop_transfers": lambda _: engine.queue(),
            "pause_transfer_node": lambda a: engine.pause(a["nodeId"], context=a["_context"]),
            "resume_transfer_node": lambda a: engine.resume(a["nodeId"], api_base=a["apiBaseUrl"], context=a["_context"]),
            "cancel_transfer_node": lambda a: engine.cancel(a["nodeId"], context=a["_context"]),
            "cancel_desktop_upload": lambda a: engine.cancel(a["uploadItemId"], context=a["_context"]),
            "clear_finished_transfers": lambda a: engine.clear_finished(context=a["_context"]),
        }

    def emit_event(self, name, payload):
        self.native_event.emit(name, (self.engine.session.generation, payload))

    @Slot(str, object)
    def receive_event(self, name, payload):
        generation, payload = payload
        if name == "session:ended":
            self.close_expired_players()
        if generation != self.engine.session.generation:
            return
        self.event.emit(json.dumps({"name": name, "payload": payload}))

    @Slot(str, str, str, str)
    def show_player(self, request_id, identity, url, name):
        request = self.media_requests.get(request_id)
        if not request or request['intent'] != self.media_intent:
            self.gateway.revoke(identity)
            self.deliver(request_id, None, 'MEDIA_SUPERSEDED')
            return
        context = self.command_contexts.get(request_id)
        if context is not None and context != self.engine.context():
            self.gateway.revoke(identity)
            self.deliver(request_id, None, "SESSION_CHANGED")
            return
        if identity not in self.gateway.entries:
            self.deliver(request_id, None, "SESSION_CHANGED")
            return
        item = self.gateway.entries[identity]
        try:
            self.engine.session.access(item[2], item[3])
        except PocError:
            self.gateway.revoke(identity)
            self.deliver(request_id, None, "SESSION_CHANGED")
            return
        self.media_current = request
        if self.players:
            player = self.players[0]
            player.load(identity, url, name, request['locale'], request['opened_at'])
        else:
            player = PlayerWindow(self.gateway, identity, url, name, self.window,
                                  request['locale'], request['opened_at'])
            self.players.append(player)
            player.closed.connect(lambda: self.player_closed(player))
            player.retry_requested.connect(self.retry_player)
        player.show()
        self.deliver(request_id, None, "")

    def player_closed(self, player):
        if player in self.players:
            self.players.remove(player)
            self.media_intent += 1
            self.media_current = None

    @Slot(str, str)
    def player_failure(self, identity, code):
        for player in self.players:
            if player.identity == identity:
                player.stop_source()
                player.failure(code)

    @Slot()
    def retry_player(self):
        request = self.media_current
        if request and request['context'] == self.engine.context():
            self.dispatch(json.dumps({'id': str(uuid.uuid4()), 'command': 'media:open', 'args': request['args']}))

    def close_players(self):
        self.media_intent += 1
        self.media_current = None
        for player in list(self.players):
            player.close()
        for identity in list(self.gateway.entries):
            self.gateway.revoke(identity)

    def close_expired_players(self):
        current = self.engine.session.owner, self.engine.session.generation
        for player in list(self.players):
            entry = self.gateway.entries.get(player.identity)
            if entry is None or entry[2:4] != current:
                player.close()
        for identity, entry in list(self.gateway.entries.items()):
            if entry[2:4] != current:
                self.gateway.revoke(identity)

    def revoke(self, *_):
        self.engine.clear_session()
        self.close_players()

    @Slot(str, object, str)
    def deliver(self, identity, result, error):
        request = self.media_requests.pop(identity, None)
        if request and error and request['intent'] == self.media_intent:
            if self.players and self.command_contexts.get(identity) == self.engine.context():
                self.players[0].failure('MEDIA_FORBIDDEN' if error in ('PERMISSION_DENIED', 'AUTH_EXPIRED') else 'MEDIA_NETWORK_ERROR')
        context = self.command_contexts.pop(identity, None)
        if context is not None and context != self.engine.context():
            result, error = None, "SESSION_CHANGED"
        self.response.emit(json.dumps({"id": identity, "result": result, "error": error or None}))

    def background(self, identity, operation):
        def run():
            try:
                context = self.command_contexts.get(identity)
                if context is not None:
                    self.engine.require_context(context)
                result = operation()
                self.completed.emit(identity, result, "")
            except Exception as error:
                self.completed.emit(identity, None, str(error) if isinstance(error, PocError) else "COMMAND_FAILED")
        self.workers.submit(run)

    @Slot(str)
    def dispatch(self, raw):
        identity = "invalid"
        try:
            if len(raw) > 8 * 1024 * 1024:
                raise PocError("INVALID_COMMAND")
            request = json.loads(raw)
            identity, command, args = request["id"], request["command"], request.get("args", {})
            if not isinstance(identity, str) or len(identity) > 64 or not isinstance(args, dict):
                raise PocError("INVALID_COMMAND")
            if command in ("connection:set", "session:clear"):
                # Revocation cannot queue behind slow authentication workers.
                result = self.engine.configure(args["apiBaseUrl"]) if command == "connection:set" else self.engine.clear_session()
                self.close_expired_players()
                self.deliver(identity, result, "")
                return
            if command == "session:sync":
                revision = self.engine.reserve_auth()
                self.background(identity, lambda: self.engine.authenticate(args["token"], args["apiBaseUrl"], revision))
                return
            scoped = command in self.handlers and command not in ("get_desktop_settings", "save_desktop_settings") or command in (
                "upload_desktop_files", "upload_desktop_folder", "start_desktop_download_file", "start_desktop_download_folder", "media:open")
            if scoped:
                args["_context"] = self.engine.context()
                self.command_contexts[identity] = args["_context"]
            if command in self.handlers:
                self.background(identity, lambda: self.handlers[command](args))
                return
            if command == "window:minimize":
                self.window.showMinimized()
            elif command == "window:maximize":
                self.window.showNormal() if self.window.isMaximized() else self.window.showMaximized()
            elif command == "window:drag":
                self.window.windowHandle().startSystemMove()
            elif command == "window:drag-regions":
                regions = args["regions"]
                if not isinstance(regions, list) or len(regions) > 16:
                    raise PocError("INVALID_DRAG_REGION")
                self.window.centralWidget().drag_regions = [
                    QRectF(*(float(r[k]) for k in ("x", "y", "width", "height"))) for r in regions]
            elif command == "window:close":
                self.close_players()
                self.window.close()
            elif command == "desktop:focus" and self.args.debug_port:
                self.window.raise_()
                self.window.activateWindow()
                self.window.centralWidget().setFocus()
            elif command == "desktop:diagnostics" and self.args.debug_port:
                self.deliver(identity, {"active": dict(self.engine.active),
                    "authenticated": bool(self.engine.session.token),
                    "window": {"x": self.window.x(), "y": self.window.y(), "width": self.window.width(), "height": self.window.height()},
                    "dragRegions": len(self.window.centralWidget().drag_regions),
                    "dragPresses": self.window.centralWidget().drag_presses,
                    "players": [{"position": p.player.position(), "state": p.player.playbackState().name,
                                 "error": p.player.error().name, "frames": p.frame_count,
                                 "size": p.frame_size, "firstFrameSeconds": p.first_frame_at - p.opened_at if p.first_frame_at else None} for p in self.players],
                    "mediaRequests": self.gateway.active_requests}, "")
                return
            elif command == "app:theme":
                mode = args.get("theme")
                if mode not in (None, "light", "dark"):
                    raise PocError("INVALID_THEME")
                QApplication.styleHints().setColorScheme({None: Qt.ColorScheme.Unknown, "light": Qt.ColorScheme.Light, "dark": Qt.ColorScheme.Dark}[mode])
            elif command == "choose_default_download_directory":
                folder = QFileDialog.getExistingDirectory(self.window, "Choose download directory")
                self.deliver(identity, folder or None, "")
                return
            elif command in ("upload_desktop_files", "upload_desktop_folder"):
                folder = command == "upload_desktop_folder"
                if folder:
                    selected = QFileDialog.getExistingDirectory(self.window, "Choose upload folder")
                    paths = [selected] if selected else []
                else:
                    paths, _ = QFileDialog.getOpenFileNames(self.window, "Choose upload files")
                if not paths:
                    self.deliver(identity, {"selected": False}, "")
                    return
                def upload():
                    task = self.engine.upload_files(paths, args["request"], folder=folder, context=args["_context"])
                    return {"selected": True, "batchId": task["id"], "rootName": task["name"]}
                self.background(identity, upload)
                return
            elif command in ("start_desktop_download_file", "start_desktop_download_folder"):
                data = args["request"]
                folder = command == "start_desktop_download_folder"
                tree = data.get("root") if folder else None
                name = tree["fileName"] if tree else data["fileName"]
                directory = self.engine.settings["defaultDownloadDir"]
                if folder:
                    directory = directory or QFileDialog.getExistingDirectory(self.window, "Choose download directory")
                    target = str(Path(directory) / safe_name(name)) if directory else ""
                else:
                    target = str(Path(directory) / safe_name(name)) if directory else QFileDialog.getSaveFileName(self.window, "Save download", safe_name(name))[0]
                if not target:
                    self.deliver(identity, {"saved": False}, "")
                    return
                def download():
                    self.engine.download(Path(target), data, tree=tree, context=args["_context"])
                    return {"saved": True, "path": target}
                self.background(identity, download)
                return
            elif command == "media:open":
                self.media_intent += 1
                request = {'intent': self.media_intent, 'context': args['_context'],
                           'args': {k: v for k, v in args.items() if not k.startswith('_')},
                           'locale': 'zh-CN' if str(args.get('locale', '')).startswith('zh') else 'en-US',
                           'opened_at': time.monotonic()}
                self.media_requests[identity] = request
                self.media_current = request
                if self.players:
                    self.players[0].loading(args.get('fileName', 'Video'), request['locale'])
                def prepare():
                    try:
                        if request['intent'] != self.media_intent:
                            raise PocError('MEDIA_SUPERSEDED')
                        self.engine.require_context(args["_context"])
                        identity_key, url = self.gateway.register(args["fileId"], args.get("teamId"), context=args["_context"])
                        self.media_ready.emit(identity, identity_key, url, args.get("fileName", "Video"))
                    except Exception as error:
                        self.completed.emit(identity, None, str(error) if isinstance(error, PocError) else "VIDEO_OPEN_FAILED")
                self.workers.submit(prepare)
                return
            else:
                raise PocError("UNKNOWN_COMMAND")
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


class StartupError(Exception):
    pass


def prepare_host(args, root):
    web = args.web_root or root / "web-dist"
    if not (web / "index.html").is_file():
        raise StartupError("Desktop pages are missing. Run uv run python build.py in desktop first.")
    try:
        api_url(args.api_base)
    except PocError:
        raise StartupError("The API address is invalid. Use an HTTP(S) address ending in /api/v1.") from None
    if not 1 <= args.port <= 65535 or (args.debug_port is not None and not 1 <= args.debug_port <= 65535):
        raise StartupError("The desktop and debug ports must be between 1 and 65535.")
    profile = (args.profile or Path(os.environ.get("LOCALAPPDATA", str(Path.home() / ".local/share"))) / "BetterCloudDriveQt").resolve()
    marker = profile / ".bcd-qt-desktop"
    if profile.exists() and any(profile.iterdir()) and not marker.is_file():
        raise StartupError("This Profile directory belongs to another application. Choose a new empty directory with --profile.")
    profile.mkdir(parents=True, exist_ok=True)
    marker.touch()
    profile_lock = QLockFile(str(profile / "instance.lock"))
    if not profile_lock.tryLock(0):
        raise StartupError("This Profile is already open. Close its client or choose a different --profile directory.")
    try:
        server = StaticServer(web, args.port)
    except OSError:
        profile_lock.unlock()
        raise StartupError("The desktop loopback port is unavailable. Close the conflicting application or choose another --port.") from None
    return profile, profile_lock, server


def bind_lifecycle(page, window, app, bridge):
    window.revoke = bridge.revoke
    page.loadStarted.connect(bridge.revoke)
    page.renderProcessTerminated.connect(bridge.revoke)
    app.aboutToQuit.connect(bridge.revoke)


def create_application(root):
    if sys.platform == "win32":
        from ctypes import windll
        # Group the uv-launched client independently from other Python apps.
        windll.shell32.SetCurrentProcessExplicitAppUserModelID("BetterCloudDrive.Qt.Desktop")
    app = QApplication(sys.argv[:1])
    app.setApplicationName("BetterCloudDrive Qt")
    app.setOrganizationName("BetterCloudDrive")
    app.setWindowIcon(QIcon(str(root / "assets" / "app.ico")))
    return app


def main():
    # Qt media diagnostics can contain stream URLs. Never emit them verbatim.
    import re
    qInstallMessageHandler(lambda kind, context, message: print(re.sub(r"https?://[^\s\"\']+", "[url]", message), file=sys.stderr) if sys.stderr else None)
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", type=Path, help="Independent Qt profile; defaults to the user application data directory")
    parser.add_argument("--web-root", type=Path)
    parser.add_argument("--api-base", default="http://127.0.0.1:8080/api/v1")
    parser.add_argument("--port", type=int, default=5184)
    parser.add_argument("--debug-port", type=int, help="Opt-in loopback CDP for local validation only")
    args = parser.parse_args()
    if args.debug_port:
        os.environ["QTWEBENGINE_REMOTE_DEBUGGING"] = f"127.0.0.1:{args.debug_port}"
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
    app = create_application(root)
    try:
        profile, profile_lock, server = prepare_host(args, root)
    except (StartupError, OSError) as error:
        message = str(error) if isinstance(error, StartupError) else "Cannot read or create the desktop Profile. Check its directory permissions."
        QMessageBox.critical(None, "BetterCloudDrive startup failed", message)
        return 1
    with ExitStack() as cleanup:
        cleanup.callback(profile_lock.unlock)
        cleanup.callback(server.server_close)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        cleanup.callback(server.shutdown)
        window = DesktopWindow()
        window.setWindowTitle("BetterCloudDrive")
        window.setWindowFlags(Qt.WindowType.Window | Qt.WindowType.FramelessWindowHint)
        window.resize(1280, 820)
        window.setMinimumSize(960, 640)
        view = DesktopView(window)
        window.setCentralWidget(view)
        browser_profile = QWebEngineProfile("desktop", view)
        browser_profile.setPersistentStoragePath(str(profile / "web-storage"))
        browser_profile.setCachePath(str(profile / "cache"))
        browser_profile.setPersistentCookiesPolicy(QWebEngineProfile.PersistentCookiesPolicy.NoPersistentCookies)
        origin = f"http://127.0.0.1:{args.port}"
        page = Page(browser_profile, origin, view)
        page.windowCloseRequested.connect(window.close)
        page.settings().setAttribute(QWebEngineSettings.WebAttribute.PdfViewerEnabled, True)
        page.settings().setAttribute(QWebEngineSettings.WebAttribute.PluginsEnabled, True)
        page.settings().setAttribute(QWebEngineSettings.WebAttribute.JavascriptCanAccessClipboard, True)
        view.setPage(page)
        engine = Engine(profile / "native", args.api_base)
        cleanup.callback(engine.close)
        bridge = Bridge(window, engine, args)
        cleanup.callback(bridge.gateway.close)
        cleanup.callback(lambda: bridge.workers.shutdown(wait=True, cancel_futures=True))
        cleanup.callback(bridge.revoke)
        engine.emit = bridge.emit_event
        bind_lifecycle(page, window, app, bridge)
        channel = QWebChannel(page)
        channel.registerObject("bridge", bridge)
        page.setWebChannel(channel, QWebEngineScript.ScriptWorldId.ApplicationWorld)
        inject_channel(page)
        view.setUrl(QUrl(origin + "/"))
        window.show()
        return app.exec()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        # Do not expose exception text, which may contain paths or credentials.
        app = QApplication.instance()
        if app:
            QMessageBox.critical(None, "BetterCloudDrive startup failed", "Desktop initialization failed. Check the Profile permissions and rebuild the desktop pages. Try a new --profile directory if local state is damaged.")
        elif sys.stderr:
            print("DESKTOP_STARTUP_FAILED", file=sys.stderr)
        sys.exit(1)
