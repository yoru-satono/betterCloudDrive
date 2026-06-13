from __future__ import annotations

from .api import AppModel


class TagEntity(AppModel):
    id: int
    userId: int
    tagName: str
    color: str | None = None
    fileCount: int = 0
    createdAt: str = ""

