"""Black-box acceptance coverage for team background tasks."""

import subprocess
import time
import uuid
from pathlib import Path

import pytest

from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated

pytestmark = pytest.mark.core
REPO_ROOT = Path(__file__).resolve().parents[2]


def _register(client, registration_code_for):
    username = f"task_outsider_{uuid.uuid4().hex[:8]}"
    email = f"{username}@test.local"
    assert_api_ok(client.post("/api/v1/auth/register", json={
        "username": username, "password": "TestPass123!", "email": email,
        "verificationCode": registration_code_for(email),
    }))
    tokens = assert_api_ok(client.post("/api/v1/auth/login", json={
        "username": username, "password": "TestPass123!",
    }))
    return {"username": username, "headers": {"Authorization": f"Bearer {tokens['accessToken']}"}}


def _sql(statement):
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
         "-U", "bcd_user", "-d", "better_cloud_drive", "-c", statement],
        cwd=REPO_ROOT, capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def _wait_succeeded(client, headers, team_id, task_id, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        page = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/tasks?page=1&size=100", headers=headers,
        ))
        task = next((item for item in page["records"] if item["id"] == task_id), None)
        if task and task["status"] == "SUCCEEDED":
            return task
        time.sleep(0.25)
    raise AssertionError(f"team task {task_id} did not succeed")


def test_team_task_filters_pagination_download_retry_and_authorization(
        client, auth_headers, registration_code_for):
    team = assert_api_ok(client.post(
        "/api/v1/teams", json={"name": f"task_team_{uuid.uuid4().hex[:8]}"}, headers=auth_headers,
    ))
    team_id = team["id"]
    admin = _register(client, registration_code_for)
    assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/members",
        json={"username": admin["username"], "role": "ADMIN"},
        headers=auth_headers,
    ))
    folder = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/files/folder",
        json={"parentId": None, "folderName": "task-folder"}, headers=auth_headers,
    ))
    audit = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/audit-logs/export-tasks", headers=auth_headers,
    ))
    archive = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/download/folders/{folder['id']}/zip/tasks", headers=auth_headers,
    ))

    first = assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/tasks?page=1&size=1", headers=auth_headers,
    ))
    second = assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/tasks?page=2&size=1", headers=auth_headers,
    ), expected_page=2)
    assert first["total"] >= 2 and first["records"][0]["id"] != second["records"][0]["id"]
    zip_page = assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/tasks?type=FOLDER_ZIP&page=1&size=20", headers=auth_headers,
    ))
    assert any(item["id"] == archive["id"] for item in zip_page["records"])

    _wait_succeeded(client, auth_headers, team_id, audit["id"])
    _wait_succeeded(client, auth_headers, team_id, archive["id"])
    assert client.get(f"/api/v1/teams/{team_id}/tasks/{audit['id']}/download", headers=auth_headers).content
    assert client.get(f"/api/v1/teams/{team_id}/tasks/{archive['id']}/download", headers=auth_headers).content
    assert_api_error(
        client.get(f"/api/v1/teams/{team_id}/tasks/{archive['id']}/download", headers=admin["headers"]),
        404,
    )

    _sql(f"UPDATE team_tasks SET status='FAILED', error_message_key='task.failed', updated_at=CURRENT_TIMESTAMP "
         f"WHERE id={archive['id']} AND team_id={team_id}")
    failed = assert_paginated(client.get(
        f"/api/v1/teams/{team_id}/tasks?status=FAILED&page=1&size=20", headers=auth_headers,
    ))
    assert any(item["id"] == archive["id"] for item in failed["records"])

    outsider = _register(client, registration_code_for)
    hidden = client.get(f"/api/v1/teams/{team_id}/tasks", headers=outsider["headers"])
    assert_api_error(hidden, 403)
    denied = client.post(f"/api/v1/teams/{team_id}/tasks/{archive['id']}/retry", headers=outsider["headers"])
    assert_api_error(denied, 403)

    retried = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/tasks/{archive['id']}/retry", headers=auth_headers,
    ))
    assert retried["status"] == "PENDING" and retried["retryCount"] >= 1
