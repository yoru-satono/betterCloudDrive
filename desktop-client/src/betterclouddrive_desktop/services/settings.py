from __future__ import annotations

import json
from pathlib import Path

APP_ORG = "BetterCloudDrive"
APP_NAME = "BetterCloudDriveDesktop"
DEFAULT_API_BASE_URL = "http://127.0.0.1:8080/api/v1"
DEFAULT_WEB_BASE_URL = "http://127.0.0.1:3000"


class SettingsService:
    def __init__(self) -> None:
        self._qsettings = self._create_qsettings()
        self._fallback_path = Path.home() / ".config" / "betterclouddrive-desktop" / "settings.json"
        self._fallback: dict[str, str] = {}
        if self._qsettings is None:
            self._fallback = self._load_fallback()

    @property
    def api_base_url(self) -> str:
        return self.get("api_base_url", DEFAULT_API_BASE_URL)

    @api_base_url.setter
    def api_base_url(self, value: str) -> None:
        self.set("api_base_url", normalize_api_base_url(value))

    @property
    def web_base_url(self) -> str:
        return self.get("web_base_url", DEFAULT_WEB_BASE_URL).rstrip("/")

    @web_base_url.setter
    def web_base_url(self, value: str) -> None:
        self.set("web_base_url", value.strip().rstrip("/") or DEFAULT_WEB_BASE_URL)

    def get(self, key: str, default: str = "") -> str:
        if self._qsettings is not None:
            value = self._qsettings.value(key, default)
            return str(value) if value is not None else default
        return self._fallback.get(key, default)

    def set(self, key: str, value: str) -> None:
        if self._qsettings is not None:
            self._qsettings.setValue(key, value)
            self._qsettings.sync()
            return
        self._fallback[key] = value
        self._save_fallback()

    def remove(self, key: str) -> None:
        if self._qsettings is not None:
            self._qsettings.remove(key)
            self._qsettings.sync()
            return
        self._fallback.pop(key, None)
        self._save_fallback()

    def _create_qsettings(self):
        try:
            from PySide6.QtCore import QSettings
        except Exception:
            return None
        return QSettings(APP_ORG, APP_NAME)

    def _load_fallback(self) -> dict[str, str]:
        try:
            return json.loads(self._fallback_path.read_text(encoding="utf-8"))
        except Exception:
            return {}

    def _save_fallback(self) -> None:
        self._fallback_path.parent.mkdir(parents=True, exist_ok=True)
        self._fallback_path.write_text(json.dumps(self._fallback, ensure_ascii=False, indent=2), encoding="utf-8")


def normalize_api_base_url(value: str) -> str:
    value = value.strip().rstrip("/")
    if not value:
        return DEFAULT_API_BASE_URL
    if not value.endswith("/api/v1"):
        value = f"{value}/api/v1"
    return value

