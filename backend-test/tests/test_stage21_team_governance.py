"""Stage 21 real-API acceptance journeys for team audit and task governance."""

from concurrent.futures import ThreadPoolExecutor
import subprocess
import threading
import time
import uuid
from pathlib import Path

import pytest

from tests.helpers.assert_helper import assert_api_error, assert_api_ok, assert_paginated
from tests.test_teams import add_member, create_team, register_user

pytestmark = [pytest.mark.core, pytest.mark.admin]
REPO_ROOT = Path(__file__).resolve().parents[2]


def _sql(statement):
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1",
         "-U", "bcd_user", "-d", "better_cloud_drive", "-c", statement],
        cwd=REPO_ROOT, capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def _wait_task(client, headers, team_id, task_id, status="SUCCEEDED", timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        records = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/tasks", params={"page": 1, "size": 100}, headers=headers,
        ))["records"]
        task = next((item for item in records if item["id"] == task_id), None)
        if task is not None and task["status"] == status:
            return task
        time.sleep(0.25)
    raise AssertionError(f"team task {task_id} did not reach {status}")


def test_audit_filters_async_export_and_role_matrix(client, auth_headers, admin_headers, registration_code_for):
    admin = register_user(client, registration_code_for, "stage21_admin")
    editor = register_user(client, registration_code_for, "stage21_editor")
    team = create_team(client, auth_headers, f"stage21_{uuid.uuid4().hex[:8]}")
    team_id = team["id"]
    add_member(client, auth_headers, team_id, admin["username"], "ADMIN")
    add_member(client, auth_headers, team_id, editor["username"], "EDITOR")

    try:
        filtered = assert_paginated(client.get(
            f"/api/v1/teams/{team_id}/audit-logs",
            params={"actorUserId": team["ownerId"], "action": "MEMBER_ADD", "targetType": "USER",
                    "result": 1, "page": 1, "size": 20},
            headers=admin["headers"],
        ))
        assert len(filtered["records"]) >= 2
        assert all(item["actorUserId"] == team["ownerId"] for item in filtered["records"])
        assert all(item["action"] == "MEMBER_ADD" and item["targetType"] == "USER" for item in filtered["records"])
        assert_api_error(client.get(f"/api/v1/teams/{team_id}/audit-logs", headers=editor["headers"]), 403)
        assert_api_error(client.post(f"/api/v1/teams/{team_id}/audit-logs/export-tasks", headers=editor["headers"]), 403)
        for params in (
            {"actorUserId": 0},
            {"result": 2},
            {"startAt": "2026-09-10T00:00:00", "endAt": "2026-09-09T00:00:00"},
        ):
            assert_api_error(client.get(
                f"/api/v1/teams/{team_id}/audit-logs", params=params, headers=admin["headers"],
            ), 400)

        task = assert_api_ok(client.post(
            f"/api/v1/teams/{team_id}/audit-logs/export-tasks",
            params={"action": "MEMBER_ADD", "result": 1}, headers=admin["headers"],
        ))
        completed = _wait_task(client, admin["headers"], team_id, task["id"])
        assert completed["resultFileName"].endswith(".csv")
        for internal_field in ("payload", "resultPath", "traceparent", "tracestate"):
            assert internal_field not in task
            assert internal_field not in completed
        download = client.get(f"/api/v1/teams/{team_id}/tasks/{task['id']}/download", headers=admin["headers"])
        assert download.status_code == 200
        assert b"MEMBER_ADD" in download.content
        assert_api_error(client.get(
            f"/api/v1/teams/{team_id}/tasks/{task['id']}/download", headers=editor["headers"],
        ), 404)
    finally:
        client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
        client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers)


def test_concurrent_task_retry_is_enqueued_once(client, auth_headers, admin_headers):
    team = create_team(client, auth_headers, f"stage21_retry_{uuid.uuid4().hex[:8]}")
    team_id = team["id"]
    task = assert_api_ok(client.post(
        f"/api/v1/teams/{team_id}/audit-logs/export-tasks", headers=auth_headers,
    ))
    _wait_task(client, auth_headers, team_id, task["id"])
    _sql(f"UPDATE team_tasks SET status='FAILED', retry_count=0, error_message_key='task.failed' "
         f"WHERE id={task['id']} AND team_id={team_id}")
    barrier = threading.Barrier(2)

    def retry(_):
        barrier.wait(timeout=5)
        return client.post(
            f"/api/v1/teams/{team_id}/tasks/{task['id']}/retry", headers=auth_headers,
        ).json()

    try:
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(retry, range(2)))
        assert sum(result["code"] == 200 for result in results) == 1, results
        assert sum(result["code"] == 400 for result in results) == 1, results
    finally:
        client.delete(f"/api/v1/teams/{team_id}", headers=auth_headers)
        client.delete(f"/api/v1/admin/teams/{team_id}/purge", headers=admin_headers)
