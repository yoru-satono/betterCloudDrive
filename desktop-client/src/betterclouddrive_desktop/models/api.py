from __future__ import annotations

from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict

T = TypeVar("T")


class AppModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class ApiEnvelope(AppModel, Generic[T]):  # noqa: UP046 - keep Python 3.12-compatible generic syntax.
    code: int
    message: str = ""
    data: T | None = None
    timestamp: int | None = None
    requestId: str | None = None


class PageResult(AppModel, Generic[T]):  # noqa: UP046 - keep Python 3.12-compatible generic syntax.
    records: list[T]
    total: int = 0
    page: int = 1
    size: int = 20
    pages: int = 0
