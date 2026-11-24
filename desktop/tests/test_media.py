import subprocess
import threading
import time

import pytest
import imageio_ffmpeg

from test_engine import system
from test_host import qt


@pytest.mark.parametrize("name,codecs", [
    ("sample.mp4", ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart"]),
    ("sample.webm", ["-c:v", "libvpx-vp9", "-c:a", "libopus"]),
])
def test_native_player_decodes_audio_video_seeks_and_revokes(system, qt, tmp_path, name, codecs):
    from PySide6.QtMultimedia import QMediaPlayer, QAudioBufferOutput
    from media import MediaGateway, PlayerWindow
    engine, api = system
    path = tmp_path / name
    subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "lavfi",
                    "-i", "testsrc=size=320x180:rate=12", "-f", "lavfi",
                    "-i", "sine=frequency=440", "-t", "3", *codecs, str(path)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    api.content = path.read_bytes()
    gateway = MediaGateway(engine)
    identity, url = gateway.register(1)
    player = PlayerWindow(gateway, identity, url, name)
    player.audio.setMuted(True)
    output = QAudioBufferOutput()
    player.player.setAudioBufferOutput(output)
    state = {"frames": 0, "audio": 0, "seek": False, "post": 0, "ended": False, "errors": []}
    def frame(value):
        if value.isValid():
            state["frames"] += 1
            assert not value.toImage().isNull()
            if state["seek"] and value.startTime() >= 1500000:
                state["post"] += 1
    def position(value):
        if value >= 400 and not state["seek"]:
            state["seek"] = True
            player.player.setPosition(1500)
    player.video.videoSink().videoFrameChanged.connect(frame)
    output.audioBufferReceived.connect(lambda value: state.update(audio=state["audio"] + int(value.isValid())))
    player.player.positionChanged.connect(position)
    player.player.mediaStatusChanged.connect(lambda value: state.update(
        ended=state["ended"] or value == QMediaPlayer.MediaStatus.EndOfMedia))
    player.player.errorOccurred.connect(lambda *_: state["errors"].append("PLAYBACK_ERROR"))
    player.show()
    try:
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and not state["ended"] and not state["errors"]:
            qt.processEvents()
            time.sleep(0.01)
        assert state["ended"] and not state["errors"]
        assert state["frames"] > 0 and state["audio"] > 0 and state["seek"] and state["post"] > 0
        player.close()
        assert identity not in gateway.entries
        assert player.player.source().isEmpty()
    finally:
        player.close()
        gateway.close()
        qt.processEvents()
