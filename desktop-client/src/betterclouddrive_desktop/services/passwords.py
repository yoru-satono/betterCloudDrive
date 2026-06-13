from __future__ import annotations

import secrets
import string

PASSWORD_CHARS = string.ascii_letters + string.digits + "!@#$%^&*_-+=?"


def validate_share_password(password: str) -> None:
    if len(password) < 4 or len(password) > 16:
        raise ValueError("分享密码长度必须为 4-16 位")


def generate_share_password(length: int = 4) -> str:
    if length < 4 or length > 16:
        raise ValueError("Share password length must be between 4 and 16 characters")
    return "".join(secrets.choice(PASSWORD_CHARS) for _ in range(length))

