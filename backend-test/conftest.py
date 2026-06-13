"""Session-scoped fixtures for the backend-test project."""

import pytest
import httpx
import uuid
import os
import re
import time

BASE_URL = os.getenv("BACKEND_URL", "http://localhost:8080")
MAILPIT_URL = os.getenv("MAILPIT_URL", "http://localhost:8025")


@pytest.fixture(scope="session")
def backend_url():
    return BASE_URL


@pytest.fixture(scope="session")
def client():
    """HTTP client without auth headers (for public endpoints)."""
    with httpx.Client(base_url=BASE_URL, timeout=30) as c:
        yield c


def _clear_mailpit():
    try:
        httpx.delete(f"{MAILPIT_URL}/api/v1/messages", timeout=5)
    except httpx.HTTPError:
        pass


def _read_latest_mail_text(recipient: str):
    try:
        response = httpx.get(f"{MAILPIT_URL}/api/v1/messages", timeout=5)
        response.raise_for_status()
        body = response.json()
    except httpx.HTTPError:
        return None

    messages = body.get("messages") or body.get("Messages") or []
    matched = None
    for message in messages:
        recipients = [to.get("Address") for to in message.get("To", []) if to.get("Address")]
        if recipient in recipients or recipient in str(message):
            matched = message
            break

    message_id = (matched or {}).get("ID") or (matched or {}).get("id")
    if not message_id:
        return None

    try:
        detail = httpx.get(f"{MAILPIT_URL}/api/v1/message/{message_id}", timeout=5)
        detail.raise_for_status()
        detail_body = detail.json()
    except httpx.HTTPError:
        return None

    parts = [
        detail_body.get("Text"),
        detail_body.get("text"),
        detail_body.get("HTML"),
        detail_body.get("html"),
        detail_body.get("Subject"),
        detail_body.get("subject"),
    ]
    return "\n".join(part for part in parts if part)


def request_registration_code(client, email: str):
    _clear_mailpit()
    response = client.post("/api/v1/auth/register-code/send", json={"email": email})
    assert response.json()["code"] == 200, f"Send registration code failed: {response.json()}"

    deadline = time.time() + 15
    while time.time() < deadline:
        text = _read_latest_mail_text(email)
        match = re.search(r"\b\d{6}\b", text or "")
        if match:
            return match.group(0)
        time.sleep(0.5)

    raise AssertionError(f"Verification code email was not received for {email}")


@pytest.fixture
def registration_code_for(client):
    return lambda email: request_registration_code(client, email)


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
    verification_code = request_registration_code(client, email)

    r = client.post("/api/v1/auth/register", json={
        "username": username,
        "password": password,
        "email": email,
        "verificationCode": verification_code,
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
