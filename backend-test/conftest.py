"""Session-scoped fixtures for the backend-test project."""

import base64
import json
import pytest
import httpx
import uuid
import os
import re
import subprocess
import time
from pathlib import Path

BASE_URL = os.getenv("BACKEND_URL", "http://localhost:8080")
MAILPIT_URL = os.getenv("MAILPIT_URL", "http://localhost:8025")
REPO_ROOT = Path(__file__).resolve().parent.parent
DATABASE_ENV_FILE = REPO_ROOT / "config" / "env-files" / "database.env"
RUN_ID = os.getenv("BCD_TEST_RUN_ID", uuid.uuid4().hex[:10])


@pytest.fixture(scope="session")
def run_id():
    return RUN_ID


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


def _decode_user_id(token: str) -> int | None:
    try:
        payload = token.split(".")[1]
        payload += "=" * ((4 - len(payload) % 4) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload))["sub"])
    except Exception:
        return None


def _read_env_file(path: Path) -> dict[str, str]:
    values = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def _compose_database_config() -> dict[str, str]:
    file_values = _read_env_file(DATABASE_ENV_FILE)
    return {
        "POSTGRES_USER": file_values.get("POSTGRES_USER") or os.getenv("POSTGRES_USER") or "bcd_user",
        "POSTGRES_DB": file_values.get("POSTGRES_DB") or os.getenv("POSTGRES_DB") or "better_cloud_drive",
    }


def _promote_user_to_admin(username: str):
    db = _compose_database_config()
    escaped_username = username.replace("'", "''")
    sql = f"UPDATE users SET role = 'ROLE_ADMIN', status = 1 WHERE username = '{escaped_username}';"
    command = [
        "docker", "compose", "exec", "-T", "postgres",
        "psql", "-v", "ON_ERROR_STOP=1",
        "-U", db["POSTGRES_USER"],
        "-d", db["POSTGRES_DB"],
        "-c", sql,
    ]
    result = subprocess.run(
        command,
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(
            "Failed to promote backend-test user to ROLE_ADMIN via Docker Compose PostgreSQL.\n"
            f"Command: {' '.join(command)}\n"
            f"stdout:\n{result.stdout}\n"
            f"stderr:\n{result.stderr}\n"
            "Set ADMIN_TOKEN to a valid admin JWT to override this fixture in non-Compose environments."
        )
    if "UPDATE 1" not in result.stdout:
        raise RuntimeError(
            f"Admin promotion did not update exactly one user for username={username!r}.\n"
            f"psql output:\n{result.stdout}\n{result.stderr}"
        )


def _login_user(client, username: str, password: str):
    response = client.post("/api/v1/auth/login", json={
        "username": username,
        "password": password,
    })
    assert response.json()["code"] == 200, f"Login failed: {response.json()}"
    return response.json()["data"]


def _create_test_user(client, prefix: str = "test"):
    username = f"{prefix}_{RUN_ID}_{uuid.uuid4().hex[:6]}"
    password = "TestPass123!"
    email = f"{username}@test.local"
    verification_code = request_registration_code(client, email)

    response = client.post("/api/v1/auth/register", json={
        "username": username,
        "password": password,
        "email": email,
        "verificationCode": verification_code,
    })
    assert response.json()["code"] == 200, f"Register failed: {response.json()}"

    data = _login_user(client, username, password)
    access_token = data["accessToken"]
    return {
        "username": username,
        "password": password,
        "email": email,
        "userId": data.get("userId") or _decode_user_id(access_token),
        "accessToken": access_token,
        "refreshToken": data["refreshToken"],
    }


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
    yield _create_test_user(client)


@pytest.fixture(scope="session")
def admin_user(client):
    """Create or load an admin user for black-box admin API tests."""
    token = os.getenv("ADMIN_TOKEN")
    if token:
        return {"userId": _decode_user_id(token), "adminToken": token}

    user = _create_test_user(client, "admin")
    _promote_user_to_admin(user["username"])
    data = _login_user(client, user["username"], user["password"])
    user["accessToken"] = data["accessToken"]
    user["refreshToken"] = data["refreshToken"]
    user["userId"] = data.get("userId") or _decode_user_id(data["accessToken"])
    user["adminToken"] = data["accessToken"]
    return user


@pytest.fixture(scope="session")
def admin_headers(admin_user):
    return {"Authorization": f"Bearer {admin_user['adminToken']}"}
