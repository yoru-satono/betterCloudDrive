"""Isolated dependency recovery acceptance checks. Cleanup always restores Redis."""

import subprocess
import time

import pytest

pytestmark = pytest.mark.resilience


def _compose(*args):
    return subprocess.run(["docker", "compose", *args], capture_output=True, text=True, timeout=60)


def test_backend_recovers_after_redis_pause_without_manual_restart(client, auth_headers):
    paused = _compose("pause", "redis")
    assert paused.returncode == 0, paused.stderr
    try:
        unavailable = client.get("/api/v1/notifications/unread-count", headers=auth_headers)
        assert unavailable.status_code >= 500, unavailable.text
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
