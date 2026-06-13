from __future__ import annotations

from typing import Literal

from .api import AppModel


class UserEntity(AppModel):
    id: int
    username: str
    email: str | None = None
    emailVerified: bool | None = None
    nickname: str | None = None
    avatarUrl: str | None = None
    role: Literal["ROLE_USER", "ROLE_ADMIN"] = "ROLE_USER"
    storageUsed: int = 0
    storageQuota: int = 0
    status: int = 1
    createdAt: str = ""
    updatedAt: str = ""
    deletedAt: str | None = None


class LoginResponse(AppModel):
    accessToken: str
    refreshToken: str
    expiresIn: int

