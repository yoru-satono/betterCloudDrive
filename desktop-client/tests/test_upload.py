from pathlib import Path

from betterclouddrive_desktop.services.upload import CHUNK_SIZE, UploadService


class FakeApi:
    def __init__(self):
        self.chunks = []

    def instant_upload(self, *_args, **_kwargs):
        return None

    def init_upload(self, *_args, **_kwargs):
        class Session:
            sessionId = "sess-1"

        return Session()

    def upload_chunk(self, session_id, chunk_number, data, file_name):
        self.chunks.append((session_id, chunk_number, len(data), file_name))

    def complete_upload(self, _session_id):
        return 99


def test_compute_md5(tmp_path: Path):
    path = tmp_path / "a.txt"
    path.write_text("hello", encoding="utf-8")

    assert UploadService.compute_md5(path) == "5d41402abc4b2a76b9719d911017c592"


def test_upload_sends_chunks(tmp_path: Path):
    path = tmp_path / "big.bin"
    path.write_bytes(b"x" * (CHUNK_SIZE + 3))
    api = FakeApi()
    service = UploadService(api)  # type: ignore[arg-type]

    assert service.upload_file(path, None) == 99
    assert api.chunks == [
        ("sess-1", 0, CHUNK_SIZE, "big.bin"),
        ("sess-1", 1, 3, "big.bin"),
    ]

