"""Isolated dependency recovery acceptance checks. Cleanup always restores Redis."""

import subprocess
import time

import pytest
import subprocess
import uuid
from pathlib import Path

from tests.helpers.assert_helper import assert_api_ok

pytestmark = pytest.mark.resilience
REPO_ROOT = Path(__file__).resolve().parents[2]


def _compose(*args):
    return subprocess.run(["docker", "compose", *args], capture_output=True, text=True, timeout=60)


def _psql(sql, tuples_only=False):
    args = ["docker", "compose", "exec", "-T", "postgres", "psql", "-v", "ON_ERROR_STOP=1"]
    if tuples_only:
        args.extend(["-t", "-A"])
    args.extend(["-U", "bcd_user", "-d", "better_cloud_drive", "-c", sql])
    return subprocess.run(args, cwd=REPO_ROOT, capture_output=True, text=True, timeout=30)


def test_backend_recovers_after_redis_pause_without_manual_restart(client, auth_headers):
    paused = _compose("pause", "redis")
    assert paused.returncode == 0, paused.stderr
    try:
        unavailable = client.get("/api/v1/notifications/unread-count", headers=auth_headers)
        assert unavailable.status_code == 401 or unavailable.status_code >= 500, unavailable.text
    finally:
        restored = _compose("unpause", "redis")
        assert restored.returncode == 0, restored.stderr

    deadline = time.time() + 30
    while time.time() < deadline:
        response = client.get("/api/v1/notifications/unread-count", headers=auth_headers)
        if response.status_code == 200 and response.json().get("code") == 200:
            return
        time.sleep(1)
    raise AssertionError("backend did not recover after Redis was restored")


def test_team_task_database_scanner_completes_work_while_redis_is_paused(client, auth_headers):
    team = assert_api_ok(client.post(
        "/api/v1/teams", json={"name": f"redis_fallback_{uuid.uuid4().hex[:8]}"}, headers=auth_headers,
    ))
    task = assert_api_ok(client.post(
        f"/api/v1/teams/{team['id']}/audit-logs/export-tasks", headers=auth_headers,
    ))
    paused = _compose("pause", "redis")
    assert paused.returncode == 0, paused.stderr
    try:
        reset = _psql(
            "UPDATE team_tasks SET status='PENDING', progress=0, started_at=NULL, completed_at=NULL, "
            "result_path=NULL, result_file_name=NULL, result_content_type=NULL, result_size_bytes=NULL, "
            f"updated_at=CURRENT_TIMESTAMP WHERE id={task['id']} AND team_id={team['id']}"
        )
        assert reset.returncode == 0, reset.stdout + reset.stderr
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            status = _psql(f"SELECT status FROM team_tasks WHERE id={task['id']}", tuples_only=True)
            assert status.returncode == 0, status.stdout + status.stderr
            if status.stdout.strip() == "SUCCEEDED":
                break
            time.sleep(2)
        else:
            raise AssertionError("database fallback did not complete the pending team task while Redis was paused")
    finally:
        restored = _compose("unpause", "redis")
        assert restored.returncode == 0, restored.stderr

    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        response = client.get(
            f"/api/v1/teams/{team['id']}/tasks/{task['id']}/download", headers=auth_headers,
        )
        if response.status_code == 200 and response.content:
            return
        time.sleep(1)
    raise AssertionError("completed fallback task result was not downloadable after Redis recovered")
