from __future__ import annotations

import hashlib
import math
from collections.abc import Callable
from pathlib import Path

from betterclouddrive_desktop.api import ApiClient, ApiError

CHUNK_SIZE = 5 * 1024 * 1024
HASH_CHUNK_SIZE = 2 * 1024 * 1024
INSTANT_MISS_CODE = 419010

ProgressCallback = Callable[[float, str], None]


class UploadService:
    def __init__(self, api: ApiClient) -> None:
        self.api = api
        self._canceled: set[Path] = set()

    def cancel(self, path: Path) -> None:
        self._canceled.add(path)

    def upload_file(self, path: Path, parent_id: int | None, on_progress: ProgressCallback | None = None) -> int:
        path = Path(path)
        file_size = path.stat().st_size
        self._emit(on_progress, 0, "计算 MD5")
        md5_hash = self.compute_md5(path, lambda pct, _message: self._emit(on_progress, pct * 0.3, "计算 MD5"))

        if self._is_canceled(path):
            raise RuntimeError("上传已取消")

        try:
            instant_id = self.api.instant_upload(parent_id, path.name, file_size, md5_hash)
            if instant_id is not None:
                self._emit(on_progress, 100, "秒传完成")
                return instant_id
        except ApiError as exc:
            if exc.code != INSTANT_MISS_CODE:
                raise

        total_chunks = max(1, math.ceil(file_size / CHUNK_SIZE))
        session = self.api.init_upload(parent_id, path.name, file_size, md5_hash, total_chunks)
        self._emit(on_progress, 30, "开始上传")

        try:
            with path.open("rb") as handle:
                for index in range(total_chunks):
                    if self._is_canceled(path):
                        self.api.cancel_upload(session.sessionId)
                        raise RuntimeError("上传已取消")
                    data = handle.read(CHUNK_SIZE)
                    self.api.upload_chunk(session.sessionId, index, data, path.name)
                    progress = 30 + ((index + 1) / total_chunks) * 65
                    self._emit(on_progress, progress, f"上传分片 {index + 1}/{total_chunks}")
            file_id = self.api.complete_upload(session.sessionId)
            self._emit(on_progress, 100, "上传完成")
            return file_id
        finally:
            self._canceled.discard(path)

    @staticmethod
    def compute_md5(path: Path, on_progress: ProgressCallback | None = None) -> str:
        digest = hashlib.md5(usedforsecurity=False)
        size = path.stat().st_size
        read = 0
        with path.open("rb") as handle:
            while chunk := handle.read(HASH_CHUNK_SIZE):
                digest.update(chunk)
                read += len(chunk)
                pct = 100 if size == 0 else min(100, (read / size) * 100)
                if on_progress:
                    on_progress(pct, "计算 MD5")
        return digest.hexdigest()

    def _is_canceled(self, path: Path) -> bool:
        return Path(path) in self._canceled

    @staticmethod
    def _emit(callback: ProgressCallback | None, pct: float, message: str) -> None:
        if callback:
            callback(round(max(0, min(100, pct)), 2), message)
