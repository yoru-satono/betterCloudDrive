import httpx

from betterclouddrive_desktop.api import ApiClient, ApiError
from betterclouddrive_desktop.services.settings import SettingsService
from betterclouddrive_desktop.services.tokens import TokenPair


class MemorySettings(SettingsService):
    def __init__(self):
        self.values = {"api_base_url": "http://test/api/v1", "web_base_url": "http://web"}

    def get(self, key: str, default: str = "") -> str:
        return self.values.get(key, default)

    def set(self, key: str, value: str) -> None:
        self.values[key] = value

    def remove(self, key: str) -> None:
        self.values.pop(key, None)


class MemoryTokenStore:
    def __init__(self):
        self.tokens = TokenPair("old-access", "old-refresh")

    def load(self):
        return self.tokens

    def save(self, access_token: str, refresh_token: str) -> None:
        self.tokens = TokenPair(access_token, refresh_token)

    def clear(self) -> None:
        self.tokens = None


def json_response(data, status_code=200):
    return httpx.Response(status_code, json=data)


def test_refreshes_expired_access_token_and_retries():
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path, request.headers.get("authorization")))
        if request.url.path == "/api/v1/files" and len(calls) == 1:
            return json_response({"code": 401001, "message": "token expired"}, 401)
        if request.url.path == "/api/v1/auth/refresh":
            return json_response(
                {
                    "code": 200,
                    "data": {
                        "accessToken": "new-access",
                        "refreshToken": "new-refresh",
                        "expiresIn": 1800,
                    },
                }
            )
        return json_response({"code": 200, "data": {"records": [], "total": 0, "page": 1, "size": 50, "pages": 0}})

    client = ApiClient(MemorySettings(), MemoryTokenStore(), transport=httpx.MockTransport(handler))
    result = client.list_files()

    assert result.records == []
    assert calls[0][2] == "Bearer old-access"
    assert calls[-1][2] == "Bearer new-access"


def test_business_error_raises_api_error():
    def handler(_request: httpx.Request) -> httpx.Response:
        return json_response({"code": 409001, "message": "conflict"})

    client = ApiClient(MemorySettings(), MemoryTokenStore(), transport=httpx.MockTransport(handler))

    try:
        client.create_folder(None, "dup")
    except ApiError as exc:
        assert exc.code == 409001
        assert exc.message == "conflict"
    else:
        raise AssertionError("ApiError not raised")

