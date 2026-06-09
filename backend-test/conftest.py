"""Session-scoped fixtures for the backend-test project."""

import pytest
import httpx
import uuid
import os

BASE_URL = os.getenv("BACKEND_URL", "http://localhost:8080")


@pytest.fixture(scope="session")
def backend_url():
    return BASE_URL


@pytest.fixture(scope="session")
def client():
    """HTTP client without auth headers (for public endpoints)."""
    with httpx.Client(base_url=BASE_URL, timeout=30) as c:
        yield c


@pytest.fixture(scope="session")
def auth_headers(test_user):
    """Authorization header dict for authenticated requests."""
    return {"Authorization": f"Bearer {test_user['accessToken']}"}


@pytest.fixture(scope="session")
def test_user(client):
    """Create a unique test user once per session. Returns credentials dict."""
    username = f"test_{uuid.uuid4().hex[:10]}"
    password = "TestPass123!"
    email = f"{username}@test.local"

    r = client.post("/api/v1/auth/register", json={
        "username": username, "password": password, "email": email
    })
    assert r.json()["code"] == 200, f"Register failed: {r.json()}"

    r = client.post("/api/v1/auth/login", json={
        "username": username, "password": password
    })
    data = r.json()["data"]

    yield {
        "username": username,
        "password": password,
        "email": email,
        "userId": data.get("userId"),
        "accessToken": data["accessToken"],
        "refreshToken": data["refreshToken"],
    }
