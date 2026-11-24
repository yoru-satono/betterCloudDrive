"""Revocable authorized media gateway and one reusable native player window."""
from __future__ import annotations

import contextlib
import re
import secrets
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
from PySide6.QtCore import Qt, QUrl, Signal
from PySide6.QtGui import QKeySequence, QShortcut
from PySide6.QtMultimedia import QAudioOutput, QMediaPlayer
from PySide6.QtMultimediaWidgets import QVideoWidget
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPushButton, QSlider, QVBoxLayout, QWidget

from network import DesktopError, envelope
from media_strings import message


def signature(metadata):
    return metadata.get('fileSize'), metadata.get('md5Hash'), metadata.get('versionCount')


def byte_range(value, size):
    if value is None:
        return None
    match = re.fullmatch(r'bytes=(\d*)-(\d*)', value)
    if not match or not any(match.groups()) or size == 0:
        raise DesktopError('MEDIA_INVALID_RANGE')
    first, last = match.groups()
    if first:
        start = int(first)
        end = min(int(last), size - 1) if last else size - 1
    else:
        if int(last) <= 0:
            raise DesktopError('MEDIA_INVALID_RANGE')
        start, end = max(0, size - int(last)), size - 1
    if start >= size or end < start:
        raise DesktopError('MEDIA_INVALID_RANGE')
    return start, end


class MediaGateway(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = False

    def __init__(self, engine):
        super().__init__(('127.0.0.1', 0), MediaHandler)
        self.engine = engine
        self.entries, self.metadata, self.connections, self.responses = {}, {}, {}, {}
        self.lock = threading.RLock()
        self.slots = threading.BoundedSemaphore(6)
        self.failed = lambda *_: None
        self.requests, self.active_requests = 0, 0
        self.last_failure = None
        self.thread = threading.Thread(target=self.serve_forever, daemon=True, name='desktop-media')
        self.thread.start()

    def register(self, file_id, team_id=None, context=None):
        if type(file_id) is not int or file_id <= 0 or (team_id is not None and (type(team_id) is not int or team_id <= 0)):
            raise DesktopError('INVALID_MEDIA')
        with self.engine.lock:
            if context is not None:
                self.engine.require_context(context)
            base = self.engine.checked_base(self.engine.api_base + (f'/teams/{team_id}' if team_id is not None else ''))
            owner, generation = self.engine.session.owner, self.engine.session.generation
        with self.engine.client(owner, generation) as client:
            metadata = envelope(client.get(f'{base}/files/{file_id}'))
        self.engine.session.access(owner, generation)
        if not isinstance(metadata, dict) or metadata.get('fileType') != 'file' or type(metadata.get('fileSize')) is not int or metadata['fileSize'] < 0:
            raise DesktopError('INVALID_MEDIA')
        identity = secrets.token_urlsafe(32)
        with self.lock:
            self.entries[identity] = (base, file_id, owner, generation, threading.Event())
            self.metadata[identity] = metadata
            self.connections[identity], self.responses[identity] = set(), set()
        return identity, f'http://127.0.0.1:{self.server_port}/{identity}'

    def revoke(self, identity):
        with self.lock:
            item = self.entries.pop(identity, None)
            self.metadata.pop(identity, None)
            connections = self.connections.pop(identity, set())
            responses = self.responses.pop(identity, set())
        if item:
            item[-1].set()
        for connection in connections:
            with contextlib.suppress(OSError):
                connection.shutdown(socket.SHUT_RDWR)
        for response in responses:
            stream = response.extensions.get('network_stream')
            if stream:
                with contextlib.suppress(Exception):
                    stream.get_extra_info('socket').shutdown(socket.SHUT_RDWR)
            with contextlib.suppress(Exception):
                response.close()

    def close(self):
        for identity in list(self.entries):
            self.revoke(identity)
        self.shutdown()
        self.server_close()
        self.thread.join(timeout=2)


class MediaHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_):
        pass

    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        self.serve(True)

    def reply(self, status, size=0, content_range=None, content_type='application/octet-stream'):
        self.send_response(status)
        self.send_header('Content-Length', str(size))
        self.send_header('Content-Type', content_type)
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Connection', 'close')
        if content_range:
            self.send_header('Content-Range', content_range)
        self.end_headers()
        self.close_connection = True

    def serve(self, body):
        if self.headers.get('Host') != f'127.0.0.1:{self.server.server_port}' or self.headers.get('Origin'):
            self.reply(403)
            return
        identity = self.path[1:] if self.path.startswith('/') else ''
        with self.server.lock:
            item = self.server.entries.get(identity)
            original = self.server.metadata.get(identity)
        if not item or original is None:
            self.reply(404)
            return
        if not self.server.slots.acquire(blocking=False):
            self.reply(503)
            return
        base, file_id, owner, generation, stop = item
        committed = False
        response = None
        upstream_sockets = set()
        def trace(_name, info):
            stream = info.get('return_value')
            if hasattr(stream, 'get_extra_info'):
                connection = stream.get_extra_info('socket')
                if connection:
                    with self.server.lock:
                        if stop.is_set():
                            with contextlib.suppress(OSError):
                                connection.shutdown(socket.SHUT_RDWR)
                        else:
                            upstream_sockets.add(connection)
                            self.server.connections.get(identity, set()).add(connection)
        with self.server.lock:
            if identity not in self.server.entries:
                self.server.slots.release()
                self.reply(404)
                return
            self.server.connections[identity].add(self.connection)
            self.server.requests += 1
            self.server.active_requests += 1
        try:
            engine = self.server.engine
            engine.session.access(owner, generation)
            with engine.client(owner, generation, stop) as client:
                metadata = envelope(client.get(f'{base}/files/{file_id}', extensions={'trace': trace}))
                if stop.is_set():
                    return
                engine.session.access(owner, generation)
                if signature(metadata) != signature(original):
                    raise DesktopError('MEDIA_CHANGED')
                size = metadata['fileSize']
                content_type = metadata.get('mimeType') or 'application/octet-stream'
                if not re.fullmatch(r'[\w.+-]+/[\w.+-]+', content_type):
                    content_type = 'application/octet-stream'
                value = self.headers.get('Range')
                try:
                    selected = byte_range(value, size)
                except DesktopError:
                    self.reply(416, content_range=f'bytes */{size}')
                    return
                if not body:
                    start, end = selected or (0, size - 1)
                    self.reply(206 if selected else 200, end - start + 1,
                        f'bytes {start}-{end}/{size}' if selected else None, content_type)
                    return
                first, last = selected or (0, size - 1)
                offset = first
                # Finish each bounded backend response before a slow/paused
                # decoder can block the local socket. Spring streaming responses
                # otherwise outlive the server's asynchronous request deadline.
                while offset <= last:
                    finish = min(last, offset + 4 * 1024 * 1024 - 1)
                    with client.stream('GET', f'{base}/download/{file_id}',
                                       headers={'Range': f'bytes={offset}-{finish}', 'Accept-Encoding': 'identity'},
                                       extensions={'trace': trace}) as response:
                        with self.server.lock:
                            if identity not in self.server.entries or stop.is_set():
                                return
                            self.server.responses[identity].add(response)
                        try:
                            status = response.status_code
                            if status not in (200, 206):
                                if status in (401, 403):
                                    raise DesktopError('MEDIA_FORBIDDEN')
                                raise DesktopError('MEDIA_INVALID_RANGE' if status == 416 else 'MEDIA_NETWORK_ERROR')
                            if 'application/json' in response.headers.get('content-type', ''):
                                response.read()
                                envelope(response)
                                raise DesktopError('MEDIA_NETWORK_ERROR')
                            start, end = (0, size - 1) if status == 200 else (offset, finish)
                            expected_range = f'bytes {start}-{end}/{size}'
                            if status == 206 and response.headers.get('content-range') != expected_range:
                                raise DesktopError('MEDIA_INVALID_RANGE')
                            expected_length = end - start + 1
                            if response.headers.get('content-length') is not None and response.headers['content-length'] != str(expected_length):
                                raise DesktopError('MEDIA_INVALID_RANGE')
                            data, received = bytearray(), 0
                            for content in response.iter_bytes(chunk_size=64 * 1024):
                                if stop.is_set():
                                    return
                                engine.session.access(owner, generation)
                                position = start + received
                                received += len(content)
                                if received > expected_length:
                                    raise DesktopError('MEDIA_INVALID_RANGE')
                                data.extend(content[max(0, offset - position):max(0, min(len(content), finish + 1 - position))])
                                if status == 200 and len(data) == finish - offset + 1:
                                    break  # Upstream ignored Range; discard its unrequested tail.
                            if len(data) != finish - offset + 1:
                                raise DesktopError('MEDIA_NETWORK_ERROR')
                        finally:
                            with self.server.lock:
                                self.server.responses.get(identity, set()).discard(response)
                    # A version/permission change during this fetch cannot publish
                    # bytes from another version into the existing local stream.
                    latest = envelope(client.get(f'{base}/files/{file_id}', extensions={'trace': trace}))
                    if signature(latest) != signature(original):
                        raise DesktopError('MEDIA_CHANGED')
                    engine.session.access(owner, generation)
                    if stop.is_set():
                        return
                    if not committed:
                        self.reply(206 if selected else 200, last - first + 1,
                                   f'bytes {first}-{last}/{size}' if selected else None, content_type)
                        committed = True
                    self.wfile.write(data)
                    offset = finish + 1
                if size == 0:
                    self.reply(200, 0, content_type=content_type)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Decoder abandoned an old range while seeking.
        except Exception as error:
            if not stop.is_set():
                code = str(error) if isinstance(error, DesktopError) else 'MEDIA_NETWORK_ERROR'
                if code in ('PERMISSION_DENIED', 'AUTH_EXPIRED', 'SESSION_CHANGED'):
                    code = 'MEDIA_FORBIDDEN'
                self.server.failed(identity, code)
                self.server.last_failure = {'code': code, 'exceptionType': type(error).__name__, 'headersSent': committed,
                                            'received': locals().get('received'), 'expected': locals().get('expected_length')}
                if not committed:
                    with contextlib.suppress(OSError):
                        self.reply(403 if code == 'MEDIA_FORBIDDEN' else 502)
        finally:
            with self.server.lock:
                self.server.connections.get(identity, set()).discard(self.connection)
                self.server.connections.get(identity, set()).difference_update(upstream_sockets)
                self.server.responses.get(identity, set()).discard(response)
                self.server.active_requests -= 1
            self.server.slots.release()
            self.close_connection = True


class PlayerWindow(QWidget):
    closed = Signal()
    retry_requested = Signal()
    frame_seen = Signal(object)

    def __init__(self, gateway, identity, url, name, parent=None, locale='en-US', opened_at=None):
        super().__init__(parent, Qt.WindowType.Window)
        self.gateway, self.identity = gateway, ''
        self.locale = locale
        self.source_revision = 0
        self.closing = False
        self.player = self.audio = None
        self.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose)
        self.resize(960, 620)
        self.video = QVideoWidget()
        self.play_button = QPushButton()
        self.play_button.clicked.connect(self.toggle)
        self.fullscreen_button = QPushButton()
        self.fullscreen_button.clicked.connect(self.fullscreen)
        self.position = QSlider(Qt.Orientation.Horizontal)
        self.position.sliderReleased.connect(lambda: self.player.setPosition(self.position.value()) if self.player else None)
        self.volume = QSlider(Qt.Orientation.Horizontal)
        self.volume.setRange(0, 100)
        self.volume.setValue(70)
        self.volume.setMaximumWidth(120)
        self.volume.valueChanged.connect(lambda value: self.audio.setVolume(value / 100) if self.audio else None)
        self.volume_label = QLabel()
        self.retry_button = QPushButton()
        self.retry_button.clicked.connect(self.retry_requested)
        self.retry_button.hide()
        self.message = QLabel()
        controls = QHBoxLayout()
        for widget in (self.play_button, self.position, self.volume_label, self.volume, self.fullscreen_button, self.retry_button):
            controls.addWidget(widget)
        layout = QVBoxLayout(self)
        layout.addWidget(self.video, 1)
        layout.addLayout(controls)
        layout.addWidget(self.message)
        self.space = QShortcut(QKeySequence(Qt.Key.Key_Space), self)
        self.space.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
        self.space.setAutoRepeat(False)
        self.space.activated.connect(self.toggle)
        self.escape = QShortcut(QKeySequence(Qt.Key.Key_Escape), self)
        self.escape.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
        self.escape.activated.connect(lambda: self.showNormal() if self.isFullScreen() else None)
        self.video.videoSink().videoFrameChanged.connect(self.observe_frame)
        self.load(identity, url, name, locale, opened_at)

    def stop_source(self):
        self.source_revision += 1
        if self.identity:
            self.gateway.revoke(self.identity)
        if self.player:
            self.audio.setMuted(True)
            self.player.stop()
            self.player.setSource(QUrl())
            self.player.setVideoOutput(None)
            self.player.setAudioOutput(None)
        self.identity = ''

    def loading(self, name, locale='en-US'):
        self.stop_source()
        self.setWindowTitle(name)
        self.locale = locale
        self.position.setValue(0)
        self.position.setEnabled(False)
        self.play_button.setEnabled(False)
        self.play_button.setText(message('play', locale))
        self.message.setText(message('loading', locale))
        self.retry_button.hide()

    def load(self, identity, url, name, locale='en-US', opened_at=None):
        self.stop_source()
        if self.player:
            self.player.deleteLater()
            self.audio.deleteLater()
        self.locale, self.identity = locale, identity
        self.last_error_code = None
        self.opened_at = opened_at or time.monotonic()
        self.first_frame_at, self.frame_count, self.last_frame_at, self.frame_size = None, 0, None, (0, 0)
        self.setWindowTitle(name)
        self.player = QMediaPlayer(self)
        self.audio = QAudioOutput(self)
        self.audio.setVolume(self.volume.value() / 100)
        self.player.setAudioOutput(self.audio)
        self.player.setVideoOutput(self.video)
        revision = self.source_revision
        valid = lambda: revision == self.source_revision and not self.closing
        self.player.durationChanged.connect(lambda duration: self.position.setRange(0, duration) if valid() else None)
        self.player.seekableChanged.connect(lambda seekable: self.position.setEnabled(seekable) if valid() else None)
        self.player.positionChanged.connect(lambda position: self.progress(position) if valid() else None)
        self.player.playbackStateChanged.connect(lambda state: self.play_button.setText(message('pause' if state == QMediaPlayer.PlaybackState.PlayingState else 'play', self.locale)) if valid() else None)
        self.player.errorOccurred.connect(lambda error, _: self.failure('MEDIA_FORMAT_ERROR' if error == QMediaPlayer.Error.FormatError else 'MEDIA_NETWORK_ERROR') if valid() else None)
        self.volume_label.setText(message('volume', locale))
        self.fullscreen_button.setText(message('fullscreen', locale))
        self.retry_button.setText(message('retry', locale))
        self.play_button.setEnabled(True)
        self.position.setValue(0)
        self.position.setEnabled(False)
        self.retry_button.hide()
        self.message.setText(message('loading', locale))
        self.player.setSource(QUrl(url))
        self.player.play()

    def observe_frame(self, frame):
        if frame.isValid() and self.identity and not self.closing:
            now = time.monotonic()
            self.frame_count += 1
            self.last_frame_at = now
            self.frame_size = frame.width(), frame.height()
            if self.first_frame_at is None:
                self.first_frame_at = now
                self.message.clear()
            self.frame_seen.emit(frame)

    def failure(self, code):
        if self.closing:
            return
        self.stop_source()
        self.last_error_code = code
        self.play_button.setText(message('play', self.locale))
        self.play_button.setEnabled(False)
        self.position.setEnabled(False)
        self.message.setText(message(code, self.locale))
        self.retry_button.show()

    def toggle(self):
        if not self.player or not self.identity:
            return
        if self.player.playbackState() == QMediaPlayer.PlaybackState.PlayingState:
            self.player.pause()
        else:
            if self.player.mediaStatus() == QMediaPlayer.MediaStatus.EndOfMedia:
                self.player.setPosition(0)
            self.player.play()

    def progress(self, position):
        if not self.position.isSliderDown():
            self.position.setValue(position)

    def fullscreen(self):
        self.showNormal() if self.isFullScreen() else self.showFullScreen()

    def closeEvent(self, event):
        if not self.closing:
            self.closing = True
            self.stop_source()
            self.closed.emit()
        super().closeEvent(event)
