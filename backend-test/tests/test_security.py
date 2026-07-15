"""Security acceptance tests for authentication boundaries and cross-user isolation."""

import uuid

import pytest


pytestmark = pytest.mark.security


def _create_user(client, registration_code_for, prefix):
    username = f"{prefix}_{uuid.uuid4().hex[:8]}"
    password = "TestPass123!"
    email = f"{username}@test.local"
    code = registration_code_for(email)
    registered = client.post("/api/v1/auth/register", json={
        "username": username,
        "password": password,
        "email": email,
        "verificationCode": code,
    })
    assert registered.json()["code"] == 200, registered.text
    login = client.post("/api/v1/auth/login", json={"username": username, "password": password})
    assert login.json()["code"] == 200, login.text
    return login.json()["data"]


def _assert_denied(response):
    if response.status_code in (401, 403, 404):
        return
    assert response.status_code == 200, response.text
    body = response.json()
    code = int(body.get("code", 0))
    assert code in (401, 403, 404) or code // 1000 in (401, 403, 404), response.text


def test_malformed_and_refresh_tokens_cannot_authenticate_api_requests(client, test_user):
    _assert_denied(client.get("/api/v1/files", headers={"Authorization": "Bearer malformed.jwt"}))
    _assert_denied(client.get(
        "/api/v1/files",
        headers={"Authorization": f"Bearer {test_user['refreshToken']}"},
    ))


def test_private_download_requires_bearer_or_scoped_ticket(client, uploaded_file):
    _assert_denied(client.get(f"/api/v1/download/{uploaded_file['fileId']}"))
    _assert_denied(client.get(f"/api/v1/download/{uploaded_file['fileId']}?ticket=tampered"))


def test_other_user_cannot_read_mutate_download_or_version_owner_file(
        client, registration_code_for, uploaded_file):
    other = _create_user(client, registration_code_for, "security_idor")
    headers = {"Authorization": f"Bearer {other['accessToken']}"}
    file_id = uploaded_file["fileId"]

    _assert_denied(client.get(f"/api/v1/files/{file_id}", headers=headers))
    _assert_denied(client.put(
        f"/api/v1/files/{file_id}", json={"newName": "stolen.txt"}, headers=headers,
    ))
    _assert_denied(client.post(f"/api/v1/files/{file_id}/move", json={"targetParentId": None}, headers=headers))
    _assert_denied(client.get(f"/api/v1/download/{file_id}", headers=headers))
    _assert_denied(client.get(f"/api/v1/files/{file_id}/versions", headers=headers))
    _assert_denied(client.post(f"/api/v1/favorites/{file_id}", headers=headers))


def test_logged_out_access_token_cannot_be_replayed(client, registration_code_for):
    user = _create_user(client, registration_code_for, "security_logout")
    headers = {"Authorization": f"Bearer {user['accessToken']}"}
    logout = client.post("/api/v1/auth/logout", json={"refreshToken": user["refreshToken"]}, headers=headers)
    assert logout.json()["code"] == 200, logout.text
    _assert_denied(client.get("/api/v1/files", headers=headers))


@pytest.mark.parametrize("path", [
    "/api/v1/files/%2e%2e%2fadmin",
    "/api/v1/files/%2Fetc%2Fpasswd",
    "/webdav/%2e%2e/%2e%2e/etc/passwd",
])
def test_encoded_path_traversal_is_rejected(client, auth_headers, path):
    response = client.get(path, headers=auth_headers)
    assert response.status_code in (400, 403, 404), response.text
