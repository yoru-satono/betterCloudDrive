import json
import subprocess
import time
import uuid
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
RABBIT_ENV = REPO_ROOT / "config" / "env-files" / "rabbitmq.env"

pytestmark = pytest.mark.observability


def _publish_invalid_task(run_id: str) -> str:
    marker = f"bcd-e2e:{run_id}:{uuid.uuid4().hex}"
    payload = json.dumps({"targetType": "INVALID_TEST_TASK", "testMarker": marker}, separators=(",", ":"))
    properties = json.dumps({
        "message_id": f"bcd-e2e-{uuid.uuid4()}",
        "content_type": "application/json",
        "delivery_mode": 2,
        "headers": {"request_id": marker},
    }, separators=(",", ":"))
    rabbit_env = dict(
        line.split("=", 1) for line in RABBIT_ENV.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
    )
    result = subprocess.run([
        "docker", "compose", "exec", "-T", "rabbitmq", "rabbitmqadmin",
        "--username", rabbit_env["RABBITMQ_DEFAULT_USER"],
        "--password", rabbit_env["RABBITMQ_DEFAULT_PASS"],
        "publish", "message", "--non-interactive",
        "--exchange", "bcd.events", "--routing-key", "team-task.wakeup",
        "--payload", payload, "--properties", properties,
    ], cwd=REPO_ROOT, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr
    return marker


def _wait_for_record(client, admin_headers, marker: str):
    deadline = time.time() + 30
    while time.time() < deadline:
        response = client.get("/api/v1/admin/dead-letters", params={
            "queue": "team-tasks", "category": "invalid-payload", "status": "OPEN",
        }, headers=admin_headers)
        assert response.status_code == 200, response.text
        records = response.json()["data"]["records"]
        match = next((record for record in records if record.get("requestId") == marker), None)
        if match:
            return match
        time.sleep(0.5)
    raise AssertionError(f"dead letter with marker {marker} was not persisted")


def test_dead_letter_admin_authorization_and_safe_resolution(
        client, auth_headers, admin_headers, admin_user, run_id):
    marker = _publish_invalid_task(run_id)
    record = _wait_for_record(client, admin_headers, marker)

    for method, path, body in (
        ("GET", "/api/v1/admin/dead-letters", None),
        ("GET", f"/api/v1/admin/dead-letters/{record['id']}", None),
        ("POST", "/api/v1/admin/dead-letters/replay", {"ids": [record["id"]]}),
        ("POST", f"/api/v1/admin/dead-letters/{record['id']}/discard", {"reason": "not authorized"}),
    ):
        response = client.request(method, path, json=body, headers=auth_headers)
        assert response.status_code == 403, response.text

    detail = client.get(f"/api/v1/admin/dead-letters/{record['id']}", headers=admin_headers)
    assert detail.status_code == 200
    assert "payload" not in detail.json()["data"]

    replay = client.post("/api/v1/admin/dead-letters/replay",
                         json={"ids": [record["id"]]}, headers=admin_headers)
    assert replay.status_code == 200, replay.text
    assert replay.json()["data"][0]["status"] == "REPLAYED"

    duplicate = client.post("/api/v1/admin/dead-letters/replay",
                            json={"ids": [record["id"]]}, headers=admin_headers)
    assert duplicate.status_code == 200, duplicate.text
    assert duplicate.json()["code"] == 409, duplicate.text

    discard_marker = _publish_invalid_task(run_id)
    discard_record = _wait_for_record(client, admin_headers, discard_marker)
    discarded = client.post(f"/api/v1/admin/dead-letters/{discard_record['id']}/discard",
                            json={"reason": "automated test fixture"}, headers=admin_headers)
    assert discarded.status_code == 200, discarded.text
    assert discarded.json()["data"]["status"] == "DISCARDED"

    logs = client.get("/api/v1/admin/logs", params={
        "userId": admin_user["userId"], "actionType": "DEAD_LETTER_DISCARD",
    }, headers=admin_headers)
    deadline = time.time() + 10
    while time.time() < deadline and not logs.json()["data"]["records"]:
        time.sleep(0.5)
        logs = client.get("/api/v1/admin/logs", params={
            "userId": admin_user["userId"], "actionType": "DEAD_LETTER_DISCARD",
        }, headers=admin_headers)
    assert logs.json()["data"]["records"], logs.text
