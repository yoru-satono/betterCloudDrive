"""Visible acceptance host with read-only, test-local widget observations."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from PySide6.QtCore import QTimer, Slot
import app as host

config = json.loads(sys.stdin.readline())


class ObservedBridge(host.Bridge):
    def __init__(self, *args):
        super().__init__(*args)
        self.opens, self.release_count, self.window_count = 0, 0, 0
        self.replies = 0
        self.response.connect(self.count_reply)
        self.timer = QTimer(self); self.timer.setInterval(200); self.timer.timeout.connect(self.observe); self.timer.start()

    @Slot(str, str, str, str)
    def show_player(self, *args):
        super().show_player(*args)
        self.opens += 1
        if not self.players: return
        player = self.players[0]
        if not getattr(player, '_m3_observed', False):
            self.window_count += 1
            player._m3_window_number = self.window_count
            player.position.sliderReleased.connect(self.slider_released)
            player._m3_observed = True

    @Slot()
    def slider_released(self): self.release_count += 1
    @Slot(str)
    def count_reply(self, _): self.replies += 1

    def observe(self):
        values = []
        for window in self.players:
            values.append({'title': window.windowTitle(), 'windowId': window._m3_window_number,
                'state': window.player.playbackState().name, 'position': window.player.position(),
                'duration': window.player.duration(), 'volume': window.audio.volume(),
                'muted': window.audio.isMuted(), 'fullscreen': window.isFullScreen(),
                'errorText': window.message.text(), 'retryVisible': window.retry_button.isVisible(),
                'nativeCode': window.last_error_code,
                'frames': window.frame_count, 'size': window.frame_size,
                'focus': window.focusWidget().metaObject().className() if window.focusWidget() else None})
        data = {'players': values, 'opens': self.opens, 'seekReleases': self.release_count, 'nativeReplies': self.replies,
                'activeMediaRequests': self.gateway.active_requests, 'capabilities': len(self.gateway.entries)}
        path = Path(config['observations'])
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data), encoding='utf-8'); temporary.replace(path)


host.Bridge = ObservedBridge
sys.argv = ['app.py', '--profile', config['profile'], '--api-base', config['apiBase'], '--port', str(config['port']), '--debug-port', str(config['debugPort'])]
sys.exit(host.main())
