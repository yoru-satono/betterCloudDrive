from __future__ import annotations

import json
import mimetypes
from collections.abc import Iterable
from pathlib import Path
from typing import Any, TypeVar

import httpx
from pydantic import TypeAdapter

from betterclouddrive_desktop.models import (
    AccessShareResponse,
    FileEntity,
    FileVersionEntity,
    LoginResponse,
    PageResult,
    ShareLinkEntity,
    TagEntity,
    UploadSession,
    UserEntity,
)
from betterclouddrive_desktop.services.settings import SettingsService
from betterclouddrive_desktop.services.tokens import TokenStore

T = TypeVar("T")


class ApiError(RuntimeError):
    def __init__(self, message: str, code: int | None = None, status_code: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.code = code
        self.status_code = status_code


class ApiClient:
    def __init__(
        self,
        settings: SettingsService | None = None,
        token_store: TokenStore | None = None,
        transport: httpx.BaseTransport | None = None,
    ) -> None:
        self.settings = settings or SettingsService()
        self.token_store = token_store or TokenStore(self.settings)
        self._client = httpx.Client(timeout=30.0, transport=transport)
        self._tokens = self.token_store.load()

    @property
    def base_url(self) -> str:
        return self.settings.api_base_url.rstrip("/")

    @property
    def is_authenticated(self) -> bool:
        return self._tokens is not None

    def close(self) -> None:
        self._client.close()

    def set_base_urls(self, api_base_url: str, web_base_url: str) -> None:
        self.settings.api_base_url = api_base_url
        self.settings.web_base_url = web_base_url

    def login(self, username: str, password: str) -> LoginResponse:
        data = self._request("POST", "/auth/login", json={"username": username, "password": password}, auth=False)
        result = LoginResponse.model_validate(data)
        self.token_store.save(result.accessToken, result.refreshToken)
        self._tokens = self.token_store.load()
        return result

    def logout(self) -> None:
        try:
            if self._tokens:
                self._request("POST", "/auth/logout")
        finally:
            self._tokens = None
            self.token_store.clear()

    def me(self) -> UserEntity:
        return UserEntity.model_validate(self._request("GET", "/auth/me"))

    def send_registration_code(self, email: str) -> None:
        self._request("POST", "/auth/register-code/send", json={"email": email}, auth=False)

    def register(self, username: str, password: str, email: str, verification_code: str) -> None:
        self._request(
            "POST",
            "/auth/register",
            json={
                "username": username,
                "password": password,
                "email": email,
                "verificationCode": verification_code,
            },
            auth=False,
        )

    def forgot_password(self, email: str) -> None:
        self._request("POST", "/auth/forgot-password", json={"email": email}, auth=False)

    def reset_password(self, email: str, code: str, new_password: str) -> None:
        self._request(
            "POST",
            "/auth/reset-password",
            json={"email": email, "code": code, "newPassword": new_password},
            auth=False,
        )

    def list_files(
        self,
        parent_id: int | None = None,
        page: int = 1,
        size: int = 50,
        sort_by: str = "fileName",
        order: str = "asc",
    ) -> PageResult[FileEntity]:
        params: dict[str, Any] = {"page": page, "size": size, "sortBy": sort_by, "order": order}
        if parent_id is not None:
            params["parentId"] = parent_id
        data = self._request("GET", "/files", params=params)
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def search_files(self, query: str, page: int = 1, size: int = 50) -> PageResult[FileEntity]:
        data = self._request("GET", "/files/search", params={"q": query, "page": page, "size": size})
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def get_file(self, file_id: int) -> FileEntity:
        return FileEntity.model_validate(self._request("GET", f"/files/{file_id}"))

    def create_folder(self, parent_id: int | None, folder_name: str) -> FileEntity:
        return FileEntity.model_validate(
            self._request("POST", "/files/folder", json={"parentId": parent_id, "folderName": folder_name})
        )

    def rename_file(self, file_id: int, new_name: str) -> FileEntity:
        return FileEntity.model_validate(self._request("PUT", f"/files/{file_id}", json={"newName": new_name}))

    def delete_files(self, file_ids: Iterable[int]) -> None:
        self._request("DELETE", "/files", json={"fileIds": list(file_ids)})

    def move_file(self, file_id: int, target_parent_id: int | None) -> None:
        self._request("POST", f"/files/{file_id}/move", json={"targetParentId": target_parent_id})

    def copy_file(self, file_id: int, target_parent_id: int | None) -> None:
        self._request("POST", f"/files/{file_id}/copy", json={"targetParentId": target_parent_id})

    def instant_upload(self, parent_id: int | None, file_name: str, file_size: int, md5_hash: str) -> int | None:
        data = self._request(
            "POST",
            "/upload/instant",
            json={"parentId": parent_id, "fileName": file_name, "fileSize": file_size, "md5Hash": md5_hash},
        )
        if data and data.get("instant") is True:
            return int(data["fileId"])
        return None

    def init_upload(
        self,
        parent_id: int | None,
        file_name: str,
        file_size: int,
        md5_hash: str,
        total_chunks: int,
    ) -> UploadSession:
        data = self._request(
            "POST",
            "/upload/init",
            json={
                "parentId": parent_id,
                "fileName": file_name,
                "fileSize": file_size,
                "md5Hash": md5_hash,
                "totalChunks": total_chunks,
            },
        )
        return UploadSession.model_validate(data)

    def upload_chunk(self, session_id: str, chunk_number: int, data: bytes, file_name: str) -> None:
        self._request(
            "POST",
            f"/upload/{session_id}/chunk",
            params={"chunkNumber": chunk_number},
            files={"file": (file_name, data, "application/octet-stream")},
        )

    def complete_upload(self, session_id: str) -> int:
        data = self._request("POST", f"/upload/{session_id}/complete")
        return int(data["fileId"])

    def cancel_upload(self, session_id: str) -> None:
        self._request("POST", f"/upload/{session_id}/cancel")

    def upload_status(self, session_id: str) -> UploadSession:
        return UploadSession.model_validate(self._request("GET", f"/upload/{session_id}/status"))

    def download_file(self, file_id: int, target_path: Path) -> None:
        self._download("GET", f"/download/{file_id}", target_path)

    def download_folder_zip(self, file_id: int, target_path: Path) -> None:
        self._download("GET", f"/download/folders/{file_id}/zip", target_path)

    def preview_file(self, file_id: int) -> tuple[bytes, str]:
        response = self._request_raw("GET", f"/preview/{file_id}")
        content_type = response.headers.get("content-type", "application/octet-stream")
        self._raise_blob_json_error(response)
        return response.content, content_type

    def list_favorites(self, page: int = 1, size: int = 100) -> PageResult[FileEntity]:
        data = self._request("GET", "/favorites", params={"page": page, "size": size})
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def add_favorite(self, file_id: int) -> None:
        self._request("POST", f"/favorites/{file_id}")

    def remove_favorite(self, file_id: int) -> None:
        self._request("DELETE", f"/favorites/{file_id}")

    def list_recycle_bin(self, page: int = 1, size: int = 100) -> PageResult[FileEntity]:
        data = self._request("GET", "/recycle-bin", params={"page": page, "size": size})
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def restore_file(self, file_id: int) -> None:
        self._request("POST", f"/recycle-bin/{file_id}/restore")

    def permanent_delete(self, file_id: int) -> None:
        self._request("DELETE", f"/recycle-bin/{file_id}")

    def empty_recycle_bin(self) -> None:
        self._request("DELETE", "/recycle-bin")

    def list_tags(self) -> list[TagEntity]:
        return TypeAdapter(list[TagEntity]).validate_python(self._request("GET", "/tags"))

    def create_tag(self, tag_name: str, color: str | None = None) -> TagEntity:
        return TagEntity.model_validate(self._request("POST", "/tags", json={"tagName": tag_name, "color": color}))

    def update_tag(self, tag_id: int, tag_name: str, color: str | None = None) -> TagEntity:
        return TagEntity.model_validate(
            self._request("PUT", f"/tags/{tag_id}", json={"tagName": tag_name, "color": color})
        )

    def delete_tag(self, tag_id: int) -> None:
        self._request("DELETE", f"/tags/{tag_id}")

    def add_file_tag(self, file_id: int, tag_id: int) -> None:
        self._request("POST", f"/tags/{tag_id}/files", json={"fileIds": [file_id]})

    def remove_file_tag(self, file_id: int, tag_id: int) -> None:
        self._request("DELETE", f"/tags/{tag_id}/files/{file_id}")

    def list_files_by_tag(self, tag_id: int, page: int = 1, size: int = 100) -> PageResult[FileEntity]:
        data = self._request("GET", f"/tags/{tag_id}/files", params={"page": page, "size": size})
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def list_versions(self, file_id: int) -> list[FileVersionEntity]:
        return TypeAdapter(list[FileVersionEntity]).validate_python(self._request("GET", f"/files/{file_id}/versions"))

    def delete_version(self, file_id: int, version_number: int) -> None:
        self._request("DELETE", f"/files/{file_id}/versions/{version_number}")

    def create_share(self, payload: dict[str, Any]) -> ShareLinkEntity:
        return ShareLinkEntity.model_validate(self._request("POST", "/shares", json=payload))

    def list_shares(self, page: int = 1, size: int = 100) -> PageResult[ShareLinkEntity]:
        data = self._request("GET", "/shares", params={"page": page, "size": size})
        return TypeAdapter(PageResult[ShareLinkEntity]).validate_python(data)

    def get_share(self, share_id: int) -> ShareLinkEntity:
        return ShareLinkEntity.model_validate(self._request("GET", f"/shares/{share_id}"))

    def update_share(self, share_id: int, payload: dict[str, Any]) -> ShareLinkEntity:
        return ShareLinkEntity.model_validate(self._request("PUT", f"/shares/{share_id}", json=payload))

    def delete_share(self, share_id: int) -> None:
        self._request("DELETE", f"/shares/{share_id}")

    def access_share(self, share_code: str, password: str | None = None) -> AccessShareResponse:
        data = self._request(
            "POST",
            f"/shares/access/{share_code}",
            json={"password": password} if password else None,
            auth=False,
        )
        return AccessShareResponse.model_validate(data)

    def list_shared_files(
        self,
        share_code: str,
        parent_id: int | None = None,
        page: int = 1,
        size: int = 100,
    ) -> PageResult[FileEntity]:
        params: dict[str, Any] = {"page": page, "size": size}
        if parent_id is not None:
            params["parentId"] = parent_id
        data = self._request("GET", f"/shares/access/{share_code}/files", params=params, auth=False)
        return TypeAdapter(PageResult[FileEntity]).validate_python(data)

    def save_shared_item(
        self,
        share_code: str,
        file_id: int | None,
        target_parent_id: int | None,
        password: str | None = None,
    ) -> FileEntity:
        payload: dict[str, Any] = {"targetParentId": target_parent_id}
        if file_id is not None:
            payload["fileId"] = file_id
        if password:
            payload["password"] = password
        return FileEntity.model_validate(self._request("POST", f"/shares/access/{share_code}/save", json=payload))

    def download_shared_file(self, share_code: str, file_id: int, password: str | None, target_path: Path) -> None:
        self._download(
            "POST",
            f"/shares/access/{share_code}/download/{file_id}",
            target_path,
            json={"password": password} if password else None,
            auth=False,
        )

    def download_shared_folder_zip(
        self,
        share_code: str,
        file_id: int,
        password: str | None,
        target_path: Path,
    ) -> None:
        self._download(
            "POST",
            f"/shares/access/{share_code}/download/{file_id}/zip",
            target_path,
            json={"password": password} if password else None,
            auth=False,
        )

    def _request(
        self,
        method: str,
        path: str,
        *,
        auth: bool = True,
        retry: bool = True,
        **kwargs: Any,
    ) -> Any:
        response = self._request_raw(method, path, auth=auth, retry=retry, **kwargs)
        try:
            payload = response.json()
        except json.JSONDecodeError as exc:
            raise ApiError("Invalid server response", status_code=response.status_code) from exc

        code = payload.get("code")
        if response.status_code >= 400 or code != 200:
            raise ApiError(
                payload.get("message") or response.reason_phrase,
                code=code,
                status_code=response.status_code,
            )
        return payload.get("data")

    def _request_raw(
        self,
        method: str,
        path: str,
        *,
        auth: bool = True,
        retry: bool = True,
        **kwargs: Any,
    ) -> httpx.Response:
        headers = dict(kwargs.pop("headers", {}) or {})
        if auth and self._tokens:
            headers["Authorization"] = f"Bearer {self._tokens.access_token}"
        response = self._client.request(method, f"{self.base_url}{path}", headers=headers, **kwargs)

        if retry and auth and self._is_expired_token(response):
            self._refresh_tokens()
            return self._request_raw(method, path, auth=auth, retry=False, **kwargs)

        if response.status_code >= 500:
            raise ApiError(response.reason_phrase, status_code=response.status_code)
        return response

    def _download(self, method: str, path: str, target_path: Path, **kwargs: Any) -> None:
        response = self._request_raw(method, path, **kwargs)
        self._raise_blob_json_error(response)
        target_path.parent.mkdir(parents=True, exist_ok=True)
        target_path.write_bytes(response.content)

    def _refresh_tokens(self) -> None:
        if not self._tokens:
            raise ApiError("Not authenticated", code=401001, status_code=401)
        response = self._client.post(f"{self.base_url}/auth/refresh", json={"refreshToken": self._tokens.refresh_token})
        payload = response.json()
        if response.status_code >= 400 or payload.get("code") != 200:
            self.token_store.clear()
            self._tokens = None
            raise ApiError(
                payload.get("message") or "Login expired",
                code=payload.get("code"),
                status_code=response.status_code,
            )
        result = LoginResponse.model_validate(payload["data"])
        self.token_store.save(result.accessToken, result.refreshToken)
        self._tokens = self.token_store.load()

    @staticmethod
    def _is_expired_token(response: httpx.Response) -> bool:
        try:
            return response.status_code == 401 and response.json().get("code") == 401001
        except Exception:
            return False

    @staticmethod
    def _raise_blob_json_error(response: httpx.Response) -> None:
        content_type = response.headers.get("content-type", "")
        if response.status_code >= 400 or "application/json" in content_type:
            try:
                payload = response.json()
            except json.JSONDecodeError as exc:
                raise ApiError(response.reason_phrase, status_code=response.status_code) from exc
            raise ApiError(
                payload.get("message") or "Request failed",
                code=payload.get("code"),
                status_code=response.status_code,
            )


def guess_mime_type(path: Path) -> str:
    return mimetypes.guess_type(path.name)[0] or "application/octet-stream"
