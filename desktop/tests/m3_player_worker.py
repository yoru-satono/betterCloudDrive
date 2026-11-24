"""Test-only instrumentation of the complete desktop process; no control RPC.

Credentials arrive on an anonymous stdin pipe and remain in memory. Measures
actual decoded frame timestamps/dimensions, not QMediaPlayer.position alone.
"""
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from PySide6.QtCore import QTimer, Slot
from PySide6.QtMultimedia import QMediaPlayer, QAudioBufferOutput
from PySide6.QtWidgets import QApplication
import app as host
from process_metrics import sample_tree

config = {}
results, frames, resources = [], [], []
original_bridge = host.Bridge


class InstrumentedBridge(original_bridge):
    def __init__(self, *args):
        super().__init__(*args)
        self.index, self.phase, self.started = -1, 'waiting', False
        self.audio_count = 0
        self.response.connect(self.reply)
        self.window.centralWidget().loadFinished.connect(self.ready)
        self.clock = QTimer(self); self.clock.setInterval(100); self.clock.timeout.connect(self.tick); self.clock.start()
        self.sampler = QTimer(self); self.sampler.setInterval(1000); self.sampler.timeout.connect(self.sample); self.sampler.start()
        self.deadline = time.monotonic() + 45
        self.last_stats, self.last_sample = {}, time.monotonic()

    def ready(self, success):
        if self.started: return
        self.started = True
        if not success: self.fail('PAGE_LOAD_FAILED'); return
        # The controller logs in through the real Vue store. Injecting only a
        # native token here races the page's unauthenticated startup cleanup.

    def reply(self, raw):
        value = json.loads(raw)
        if value['id'] == 'authenticate':
            if value['error']: self.fail('AUTHENTICATION_FAILED')
            else: self.next_sample()
        elif value['id'].startswith('sample-') and value['error']:
            self.fail('MEDIA_OPEN_FAILED')

    @Slot()
    def next_sample(self):
        self.index += 1
        if self.index >= len(config['samples']):
            self.window.close(); QApplication.instance().quit(); return
        self.current = config['samples'][self.index]
        self.phase, self.audio_count = 'playback', 0
        frames.clear(); resources.clear()
        self.opened = time.monotonic(); self.deadline = self.opened + 150
        print('playing', self.current['name'], flush=True)
        self.dispatch(json.dumps({'id': f'sample-{self.index}', 'command': 'media:open', 'args': {
            'fileId': self.current['fileId'], 'teamId': self.current.get('teamId'),
            'fileName': self.current['name'], 'locale': 'en-US'}}))

    @Slot(str, str, str, str)
    def show_player(self, *args):
        super().show_player(*args)
        if not self.players: return
        window = self.players[0]
        if not getattr(window, '_m3_hooked', False):
            window.frame_seen.connect(self.frame); window._m3_hooked = True
        self.output = QAudioBufferOutput(window.player)
        self.output.audioBufferReceived.connect(lambda buffer: setattr(self, 'audio_count', self.audio_count + int(buffer.isValid())))
        window.player.setAudioBufferOutput(self.output)
        # The actual audio path stays active. Muting avoids eight minutes of tone.
        window.audio.setMuted(True)

    @Slot(object)
    def frame(self, frame):
        now = time.monotonic()
        if self.phase == 'playback':
            point = (now, frame.startTime(), frame.width(), frame.height())
            if not frames or point[1] != frames[-1][1]: frames.append(point)
        elif self.phase == 'seek' and frame.startTime() >= 90_000_000 and frame.startTime() < 91_000_000:
            self.seek_seconds = now - self.seek_started
            self.finish()

    def sample(self):
        now = time.monotonic(); stats = sample_tree()
        delta = sum(max(0, value['cpu'] - self.last_stats.get(pid, value)['cpu']) for pid, value in stats.items())
        if self.phase == 'playback' and self.last_stats:
            resources.append({'cpuOneCorePercent': delta / (now - self.last_sample) * 100,
                              'rssMiB': sum(v['rss'] for v in stats.values()) / 1024**2, 'processes': len(stats)})
        self.last_stats, self.last_sample = stats, now

    def tick(self):
        if time.monotonic() > self.deadline: self.fail('PLAYBACK_TIMEOUT'); return
        if self.phase == 'waiting' and self.engine.session.token:
            self.next_sample()
            return
        if not self.players or self.phase not in ('playback', 'seek'): return
        player = self.players[0].player
        if player.error() != QMediaPlayer.Error.NoError or self.players[0].retry_button.isVisible(): self.fail('NATIVE_PLAYBACK_ERROR'); return
        if self.phase == 'playback' and player.mediaStatus() == QMediaPlayer.MediaStatus.EndOfMedia:
            self.playback_frames, self.playback_resources = list(frames), list(resources)
            self.ended = time.monotonic()
            self.phase = 'seek'; self.seek_started = time.monotonic(); self.deadline = self.seek_started + 5
            # Replay uses the real button handler; seek is committed by the real slider signal.
            window = self.players[0]; window.play_button.click()
            window.position.setValue(90000); window.position.sliderReleased.emit()

    def finish(self):
        data = self.playback_frames
        stable = [p for p in data if p[0] >= data[0][0] + 3] if data else []
        fps = (len(stable)-1)/(stable[-1][0]-stable[0][0]) if len(stable) > 1 else 0
        gaps = [b[0]-a[0] for a, b in zip(stable, stable[1:])]
        if stable: gaps.append(self.ended - stable[-1][0])
        metrics = self.playback_resources
        record = {'name': self.current['name'], 'space': 'team' if self.current.get('teamId') else 'personal',
            'durationSeconds': 120, 'fps': round(fps, 3), 'maxStallSeconds': round(max(gaps, default=999), 3),
            'firstFrameSeconds': round(data[0][0]-self.opened, 3) if data else 999,
            'seekFrameSeconds': round(self.seek_seconds, 3), 'frameDimensions': sorted({(p[2], p[3]) for p in data}),
            'frames': len(data), 'audioBuffers': self.audio_count,
            'videoWidgetSize': [self.players[0].video.width(), self.players[0].video.height()],
            'displaySize': [self.players[0].screen().size().width(), self.players[0].screen().size().height()],
            'devicePixelRatio': self.players[0].devicePixelRatio(),
            'cpuOneCorePercentMean': round(sum(m['cpuOneCorePercent'] for m in metrics)/len(metrics), 2) if metrics else None,
            'cpuOneCorePercentPeak': round(max((m['cpuOneCorePercent'] for m in metrics), default=0), 2),
            'logicalProcessors': os.cpu_count(), 'rssMiBPeak': round(max((m['rssMiB'] for m in metrics), default=0), 2),
            'maxProcessCount': max((m['processes'] for m in metrics), default=0), 'hardwareDecode': 'unconfirmed'}
        record['passed'] = (fps >= 24 and record['maxStallSeconds'] <= 2 and record['firstFrameSeconds'] <= 5
            and self.seek_seconds <= 3 and self.audio_count > 0 and len(data) >= 2800
            and {tuple(v) for v in record['frameDimensions']} == {tuple(self.current['size'])})
        results.append(record)
        print('playback-result', json.dumps(record), flush=True)
        self.phase = 'between'; self.players[0].stop_source()
        QTimer.singleShot(500, self.next_sample)

    def fail(self, code):
        results.append({'name': getattr(self, 'current', {}).get('name', 'startup'), 'passed': False, 'code': code,
                        'nativeCode': self.players[0].last_error_code if self.players else None,
                        'gateway': self.gateway.last_failure,
                        'frames': self.players[0].frame_count if self.players else 0})
        self.window.close(); QApplication.instance().quit()


def main():
    global config
    config = json.loads(sys.stdin.readline())
    host.Bridge = InstrumentedBridge
    sys.argv = ['app.py', '--api-base', config['apiBase'], '--profile', config['profile'], '--port', str(config['port']), '--debug-port', str(config['debugPort'])]
    try:
        host.main()
    finally:
        Path(config['report']).write_text(json.dumps({'samples': results}, indent=2), encoding='utf-8')
    return 0 if len(results) == len(config['samples']) and all(r['passed'] for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
