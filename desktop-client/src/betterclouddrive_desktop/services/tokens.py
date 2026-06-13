from __future__ import annotations

from dataclasses import dataclass

from .settings import SettingsService

SERVICE_NAME = "betterclouddrive-desktop"


@dataclass
class TokenPair:
    access_token: str
    refresh_token: str


class TokenStore:
    def __init__(self, settings: SettingsService | None = None) -> None:
        self.settings = settings or SettingsService()

    def load(self) -> TokenPair | None:
        access = self._get_secret("access_token")
        refresh = self._get_secret("refresh_token")
        if not access or not refresh:
            return None
        return TokenPair(access, refresh)

    def save(self, access_token: str, refresh_token: str) -> None:
        self._set_secret("access_token", access_token)
        self._set_secret("refresh_token", refresh_token)

    def clear(self) -> None:
        self._delete_secret("access_token")
        self._delete_secret("refresh_token")

    def _get_secret(self, key: str) -> str | None:
        try:
            import keyring

            value = keyring.get_password(SERVICE_NAME, key)
            if value:
                return value
        except Exception:
            pass
        return self.settings.get(f"token.{key}", "") or None

    def _set_secret(self, key: str, value: str) -> None:
        try:
            import keyring

            keyring.set_password(SERVICE_NAME, key, value)
            self.settings.remove(f"token.{key}")
            return
        except Exception:
            self.settings.set(f"token.{key}", value)

    def _delete_secret(self, key: str) -> None:
        try:
            import keyring

            keyring.delete_password(SERVICE_NAME, key)
        except Exception:
            pass
        self.settings.remove(f"token.{key}")

