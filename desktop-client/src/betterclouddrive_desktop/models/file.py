from __future__ import annotations

from typing import Literal

from .api import AppModel

FileType = Literal["file", "folder"]


class FileEntity(AppModel):
    id: int
    userId: int
    parentId: int | None = None
    fileName: str
    fileType: FileType
    mimeType: str | None = None
    fileSize: int = 0
    storagePath: str | None = None
    md5Hash: str | None = None
    isDeleted: bool = False
    versionCount: int = 1
    createdAt: str = ""
    updatedAt: str = ""

    @property
    def is_folder(self) -> bool:
        return self.fileType == "folder"


class FileVersionEntity(AppModel):
    id: int
    fileId: int
    versionNumber: int
    storagePath: str
    fileSize: int = 0
    md5Hash: str | None = None
    createdAt: str = ""


class UploadSession(AppModel):
    sessionId: str
    chunkSize: int | None = None
    totalChunks: int = 1
    uploadedChunks: int | None = None
    missingChunks: list[int] | None = None

