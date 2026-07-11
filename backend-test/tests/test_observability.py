"""Acceptance checks for audit correlation and the external observability chain."""

import time
import uuid

import httpx
import pytest

pytestmark = pytest.mark.observability


def test_request_id_reaches_loki_and_grafana_is_admin_only(client, auth_headers, admin_headers):
    request_id = f"obs-{uuid.uuid4().hex}"
    response = client.get("/api/v1/auth/me", headers={**auth_headers, "X-Request-ID": request_id})
    assert response.status_code == 200
    assert response.headers.get("X-Request-ID") == request_id or response.json().get("requestId") == request_id

    deadline = time.time() + 30
    found = False
    while time.time() < deadline:
        query = httpx.get("http://localhost:3100/loki/api/v1/query_range", params={
            "query": f'{{service=~".+"}} |= "{request_id}"', "limit": 20,
        }, timeout=5)
        assert query.status_code == 200, query.text
        if query.json()["data"]["result"]:
            found = True
            break
        time.sleep(1)
    assert found, f"request ID {request_id} did not reach Loki"

    denied = client.post("/api/v1/admin/grafana/session", headers=auth_headers)
    assert denied.status_code in (401, 403) or denied.json().get("code") == 403
    allowed = client.post("/api/v1/admin/grafana/session", headers=admin_headers)
    assert allowed.status_code == 200 and allowed.json()["code"] == 200
    assert httpx.get("http://localhost:3200/ready", timeout=5).status_code == 200

