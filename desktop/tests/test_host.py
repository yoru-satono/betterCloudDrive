import json
import threading
from pathlib import Path

import httpx
import pytest

from app import StaticServer, Bridge
from test_engine import system


def test_static_server_rejects_rebinding_and_traversal(tmp_path):
    (tmp_path / "index.html").write_text("desktop")
    server = StaticServer(tmp_path, 0)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        base = f"http://127.0.0.1:{server.server_port}"
        assert httpx.get(base, trust_env=False).text == "desktop"
        assert httpx.get(base, headers={"Host": "evil.invalid"}, trust_env=False).status_code == 403
        assert httpx.get(base + "/%2e%2e/secret", trust_env=False).status_code == 403
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


@pytest.fixture(scope="session")
def qt():
    from PySide6.QtWidgets import QApplication
    app = QApplication.instance() or QApplication([])
    app.setQuitOnLastWindowClosed(False)
    return app


def test_native_picker_cancellation_and_unknown_commands(system, qt, monkeypatch):
    from types import SimpleNamespace
    from PySide6.QtWidgets import QMainWindow, QFileDialog
    engine, _ = system
    window = QMainWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    responses = []
    bridge.response.connect(lambda value: responses.append(json.loads(value)))
    monkeypatch.setattr(QFileDialog, "getOpenFileNames", lambda *_: ([], ""))
    monkeypatch.setattr(QFileDialog, "getExistingDirectory", lambda *_: "")
    try:
        for command in ("upload_desktop_files", "upload_desktop_folder", "start_desktop_download_file"):
            if command.startswith("start"):
                monkeypatch.setattr(QFileDialog, "getSaveFileName", lambda *_: ("", ""))
            bridge.dispatch(json.dumps({"id": command, "command": command,
                                       "args": {"request": {"fileName": "a"}}}))
        assert all(not value["error"] for value in responses)
        assert responses[0]["result"] == {"selected": False}
        assert responses[1]["result"] == {"selected": False}
        assert responses[2]["result"] == {"saved": False}
        bridge.dispatch('{"id":"x","command":"download_desktop_file","args":{}}')
        assert responses[-1]["error"] == "UNKNOWN_COMMAND"
        assert not engine.tasks
    finally:
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()


def test_media_gateway_range_and_revocation(system):
    from media import MediaGateway
    engine, api = system
    gateway = MediaGateway(engine)
    try:
        identity, url = gateway.register(1)
        response = httpx.get(url, headers={"Range": "bytes=3-"}, trust_env=False)
        assert response.status_code == 206 and response.content == b'def'
        assert response.headers['content-range'] == 'bytes 3-5/6'
        assert ("GET", "/api/v1/download/1", "bytes=3-5") in api.calls
        assert httpx.get(url, headers={"Host": "evil.invalid"}, trust_env=False).status_code == 403
        assert httpx.get(url, headers={"Range": "bytes=0-1,3-4"}, trust_env=False).status_code == 416
        gateway.revoke(identity)
        assert httpx.get(url, trust_env=False).status_code == 404
        _, url = gateway.register(1)
        engine.clear_session()
        assert httpx.get(url, trust_env=False).status_code == 403
    finally:
        gateway.close()


def test_only_trusted_main_pages_receive_navigation():
    from types import SimpleNamespace
    from PySide6.QtCore import QUrl
    from app import Page
    page = SimpleNamespace(origin="http://127.0.0.1:5184")
    check = lambda value, main=True: Page.acceptNavigationRequest(page, QUrl(value), None, main)
    assert check("http://127.0.0.1:5184/#/files")
    assert not check("https://evil.invalid/")
    assert not check("about:blank")
    assert not check("file:///secret")
    assert check("chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/index.html", False)


def test_startup_failures_are_specific_and_release_profile_lock(tmp_path, qt):
    from types import SimpleNamespace
    from app import prepare_host, StartupError
    from PySide6.QtCore import QLockFile
    import socket
    args = SimpleNamespace(web_root=tmp_path / 'web', profile=tmp_path / 'profile', api_base='http://127.0.0.1:8080/api/v1', port=5184, debug_port=None)
    with pytest.raises(StartupError, match='pages are missing'):
        prepare_host(args, tmp_path)
    args.web_root.mkdir()
    (args.web_root / 'index.html').write_text('desktop')
    args.profile.mkdir()
    (args.profile / 'foreign').touch()
    with pytest.raises(StartupError, match='another application'):
        prepare_host(args, tmp_path)
    (args.profile / 'foreign').unlink()
    (args.profile / '.bcd-qt-desktop').touch()
    lock = QLockFile(str(args.profile / 'instance.lock'))
    assert lock.tryLock(0)
    with pytest.raises(StartupError, match='already open'):
        prepare_host(args, tmp_path)
    lock.unlock()
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', 0))
        occupied.listen()
        args.port = occupied.getsockname()[1]
        with pytest.raises(StartupError, match='port is unavailable'):
            prepare_host(args, tmp_path)
    args.port = 0
    with pytest.raises(StartupError, match='between 1 and 65535'):
        prepare_host(args, tmp_path)
    assert lock.tryLock(0)
    lock.unlock()


def test_revocation_does_not_wait_for_queued_auth_and_window_close(system, qt):
    from types import SimpleNamespace
    from app import Bridge, DesktopWindow
    engine, _ = system
    window = DesktopWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    queued, responses = [], []
    bridge.background = lambda identity, operation: queued.append(operation)
    bridge.response.connect(lambda raw: responses.append(json.loads(raw)))
    try:
        bridge.dispatch(json.dumps({'id': 'sync', 'command': 'session:sync', 'args': {'token': 'synthetic-old', 'apiBaseUrl': engine.api_base}}))
        bridge.dispatch('{"id":"clear","command":"session:clear"}')
        assert responses[-1]['result'] == {'authenticated': False}
        with pytest.raises(Exception, match='SESSION_CHANGED'):
            queued[0]()
        window.revoke = bridge.revoke
        engine.session.replace(engine.api_base, '1', 'synthetic-token')
        window.close()
        assert not engine.session.token
    finally:
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()


def test_native_picker_selection_is_submitted_only_from_dialog(system, qt, monkeypatch, tmp_path):
    from types import SimpleNamespace
    from PySide6.QtWidgets import QMainWindow, QFileDialog
    engine, _ = system
    window = QMainWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    responses, uploads = [], []
    bridge.response.connect(lambda raw: responses.append(json.loads(raw)))
    bridge.background = lambda identity, operation: bridge.deliver(identity, operation(), '')
    def upload(paths, request, folder=False, context=None):
        uploads.append((paths, folder))
        return {'id': 'batch', 'name': 'selected'}
    monkeypatch.setattr(engine, 'upload_files', upload)
    monkeypatch.setattr(QFileDialog, 'getOpenFileNames', lambda *_: ([str(tmp_path / 'a'), str(tmp_path / 'b')], ''))
    monkeypatch.setattr(QFileDialog, 'getExistingDirectory', lambda *_: str(tmp_path))
    try:
        for command in ('upload_desktop_files', 'upload_desktop_folder', 'choose_default_download_directory'):
            bridge.dispatch(json.dumps({'id': command, 'command': command, 'args': {'request': {}, 'paths': ['untrusted-path']}}))
        assert uploads == [([str(tmp_path / 'a'), str(tmp_path / 'b')], False), ([str(tmp_path)], True)]
        assert responses[-1]['result'] == str(tmp_path)
    finally:
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()


def test_reload_and_renderer_termination_revoke_native_session(system, qt):
    from types import SimpleNamespace
    from PySide6.QtCore import QObject, Signal
    from app import Bridge, DesktopWindow, bind_lifecycle
    class PageSignals(QObject):
        loadStarted = Signal()
        renderProcessTerminated = Signal(int, int)
    class AppSignals(QObject):
        aboutToQuit = Signal()
    engine, _ = system
    window = DesktopWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    page, app = PageSignals(), AppSignals()
    bind_lifecycle(page, window, app, bridge)
    try:
        for signal, args in ((page.loadStarted, ()), (page.renderProcessTerminated, (1, 9)), (app.aboutToQuit, ())):
            engine.session.replace(engine.api_base, '1', 'synthetic-token')
            previous = engine.reserve_auth()
            signal.emit(*args)
            assert not engine.session.token
            assert engine.auth_revision > previous
    finally:
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()


def test_live_acceptance_refuses_occupied_port_before_spawning_or_attaching(monkeypatch):
    import socket
    from types import SimpleNamespace
    from validate import Validation
    from network import DesktopError
    run = object.__new__(Validation)
    run.debug_port = 9226
    monkeypatch.setattr('validate.subprocess.Popen', lambda *_args, **_kwargs: pytest.fail('Must not launch or attach'))
    with socket.socket() as occupied:
        occupied.bind(('127.0.0.1', 0))
        occupied.listen()
        run.port = occupied.getsockname()[1]
        with pytest.raises(DesktopError, match='TEST_PORT_UNAVAILABLE'):
            run.launch()


def test_late_command_response_and_native_event_cannot_restore_old_queue(system, qt):
    from types import SimpleNamespace
    from PySide6.QtWidgets import QMainWindow
    from app import Bridge
    engine, _ = system
    window = QMainWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    responses, events, queued = [], [], []
    bridge.response.connect(lambda raw: responses.append(json.loads(raw)))
    bridge.event.connect(lambda raw: events.append(json.loads(raw)))
    bridge.background = lambda identity, operation: queued.append((identity, operation))
    try:
        bridge.dispatch('{"id":"old","command":"get_transfer_queue"}')
        generation = engine.session.generation
        engine.clear_session()
        bridge.deliver('old', {'tasks': [{'name': 'old queue'}]}, '')
        assert responses[-1]['error'] == 'SESSION_CHANGED' and responses[-1]['result'] is None
        bridge.receive_event('transfer:queue-updated', (generation, {'tasks': [{'name': 'old queue'}]}))
        assert not events
        bridge.receive_event('transfer:queue-updated', (engine.session.generation, {'tasks': []}))
        assert events[-1]['payload'] == {'tasks': []}
    finally:
        queued.clear()
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()
        window.deleteLater()
        from PySide6.QtCore import QCoreApplication, QEvent
        QCoreApplication.sendPostedEvents(None, QEvent.Type.DeferredDelete)
        qt.processEvents()


def test_synchronous_session_clear_revokes_players_after_generation_changes(system, qt):
    from types import SimpleNamespace
    from PySide6.QtWidgets import QMainWindow
    from app import Bridge
    engine, _ = system
    window = QMainWindow()
    bridge = Bridge(window, engine, SimpleNamespace(debug_port=None))
    engine.emit = bridge.emit_event
    identity = 'synthetic-capability'
    bridge.gateway.entries[identity] = (engine.api_base, 1, engine.session.owner, engine.session.generation, threading.Event())
    class Player:
        closed = False
        def close(self):
            self.closed = True
            bridge.gateway.revoke(identity)
    player = Player()
    player.identity = identity
    bridge.players.append(player)
    try:
        bridge.receive_event('session:ended', (engine.session.generation - 1, None))
        assert not player.closed
        bridge.dispatch('{"id":"clear-player","command":"session:clear"}')
        assert player.closed and identity not in bridge.gateway.entries
    finally:
        engine.emit = lambda *_: None
        bridge.players.clear()
        bridge.workers.shutdown(wait=True)
        bridge.gateway.close()
        window.close()
