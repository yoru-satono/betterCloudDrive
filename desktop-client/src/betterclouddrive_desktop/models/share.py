from __future__ import annotations

from typing import Literal

from .api import AppModel


class ShareLinkEntity(AppModel):
    id: int
    userId: int
    fileId: int
    shareCode: str
    passwordHash: str | None = None
    expireAt: str | None = None
    maxVisits: int | None = None
    downloadCount: int = 0
    visitCount: int = 0
    isCanceled: bool = False
    createdAt: str = ""
    updatedAt: str = ""


class AccessShareResponse(AppModel):
    fileId: int
    fileName: str
    fileType: Literal["file", "folder"]
    fileSize: int = 0

