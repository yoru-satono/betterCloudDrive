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
    trace_id = response.headers.get("X-Trace-ID")
    assert trace_id and len(trace_id) == 32

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
    deadline = time.time() + 30
    tempo_status = None
    while time.time() < deadline:
        tempo_status = httpx.get("http://localhost:3200/ready", timeout=5)
        if tempo_status.status_code == 200:
            break
        time.sleep(1)
    assert tempo_status is not None and tempo_status.status_code == 200, tempo_status.text if tempo_status else "no response"

    deadline = time.time() + 30
    trace_found = False
    while time.time() < deadline:
        trace = httpx.get(f"http://localhost:3200/api/traces/{trace_id}", timeout=5)
        if trace.status_code == 200:
            trace_found = True
            break
        assert trace.status_code == 404, trace.text
        time.sleep(1)
    assert trace_found, f"trace ID {trace_id} from the response was not found in Tempo"


def test_prometheus_metrics_rules_and_observability_targets():
    deadline = time.time() + 45
    targets = None
    while time.time() < deadline:
        response = httpx.get("http://localhost:9090/api/v1/targets", timeout=5)
        if response.status_code == 200:
            targets = response.json()["data"]["activeTargets"]
            backend = [target for target in targets if target["labels"].get("job") == "better-cloud-drive"]
            if backend and backend[0]["health"] == "up":
                break
        time.sleep(1)
    assert targets is not None
    health_by_job = {target["labels"].get("job"): target["health"] for target in targets}
    for job in ("better-cloud-drive", "prometheus", "loki", "tempo", "otel-collector", "promtail"):
        assert health_by_job.get(job) == "up", f"Prometheus target {job} is not up: {health_by_job}"

    metrics = httpx.get("http://localhost:9090/api/v1/query", params={
        "query": 'bcd_queue_observation_available',
    }, timeout=5)
    assert metrics.status_code == 200
    assert metrics.json()["data"]["result"], metrics.text

    rules = httpx.get("http://localhost:9090/api/v1/rules", params={"type": "alert"}, timeout=5)
    assert rules.status_code == 200
    names = {
        rule["name"]
        for group in rules.json()["data"]["groups"]
        for rule in group["rules"]
    }
    assert {
        "BetterCloudDriveBackendDown",
        "BetterCloudDriveHighServerErrorRate",
        "BetterCloudDriveQueueBacklog",
        "BetterCloudDriveDeadLettersPresent",
        "BetterCloudDriveObservabilityTargetDown",
    }.issubset(names)

    alertmanager = httpx.get("http://localhost:9093/-/ready", timeout=5)
    assert alertmanager.status_code == 200
