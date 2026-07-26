"""Acceptance checks for audit correlation and the external observability chain."""

import hashlib
import json
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx
import pytest

pytestmark = pytest.mark.observability

DASHBOARD_DIR = (
    Path(__file__).resolve().parents[2]
    / "observability"
    / "grafana"
    / "provisioning"
    / "dashboards"
    / "json"
)
EXPECTED_DASHBOARDS = {
    "bcd-application-overview": "BetterCloudDrive / Service Overview",
    "bcd-storage-transfer": "BetterCloudDrive / Storage & Transfer",
    "bcd-business-operations": "BetterCloudDrive / Async & Collaboration",
    "bcd-infrastructure": "BetterCloudDrive / Infrastructure",
}
EXPECTED_DATASOURCES = {
    "bcd-prometheus": "prometheus",
    "bcd-loki": "loki",
    "bcd-tempo": "tempo",
    "bcd-alertmanager": "alertmanager",
}
GRAFANA_URL = "http://localhost:3000/grafana"


def _prometheus_query(query):
    response = httpx.get("http://localhost:9090/api/v1/query", params={"query": query}, timeout=5)
    assert response.status_code == 200, response.text
    return response.json()["data"]["result"]


def _wait_for_prometheus(query, predicate, timeout=45):
    deadline = time.time() + timeout
    samples = []
    while time.time() < deadline:
        samples = _prometheus_query(query)
        if predicate(samples):
            return samples
        time.sleep(1)
    raise AssertionError(f"Prometheus query did not reach the expected state: {query}; samples={samples}")


def _metric_total(query):
    samples = _prometheus_query(query)
    return float(samples[0]["value"][1]) if samples else 0.0


def _tamper(value):
    replacement = "A" if value[-1] != "A" else "B"
    return value[:-1] + replacement


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


def test_grafana_auth_proxy_end_to_end(auth_headers, admin_headers):
    forged_headers = {
        "X-WEBAUTH-USER": "forged-admin",
        "X-WEBAUTH-NAME": "Forged Admin",
        "X-WEBAUTH-EMAIL": "forged-admin@test.local",
        "X-WEBAUTH-ROLE": "Admin",
    }
    with httpx.Client(base_url="http://localhost:8080", timeout=30) as anonymous:
        denied = anonymous.get(f"{GRAFANA_URL}/")
        assert denied.status_code == 401, denied.text
        forged = anonymous.get(f"{GRAFANA_URL}/api/health", headers=forged_headers)
        assert forged.status_code == 401, forged.text

    with httpx.Client(base_url="http://localhost:8080", timeout=30) as ordinary:
        session = ordinary.post("/api/v1/admin/grafana/session", headers=auth_headers)
        assert session.status_code in (401, 403) or session.json().get("code") == 403
        denied = ordinary.get(
            f"{GRAFANA_URL}/api/health",
            headers={**auth_headers, **forged_headers},
        )
        assert denied.status_code == 401, denied.text

    with httpx.Client(base_url="http://localhost:8080", timeout=30) as admin:
        session = admin.post("/api/v1/admin/grafana/session", headers=admin_headers)
        assert session.status_code == 200 and session.json()["code"] == 200, session.text
        cookie = admin.cookies.get("BCD_GRAFANA_SESSION")
        assert cookie

        home = admin.get(f"{GRAFANA_URL}/")
        assert home.status_code == 200 and "Grafana" in home.text

        health = admin.get(f"{GRAFANA_URL}/api/health")
        assert health.status_code == 200, health.text
        assert health.json()["database"] == "ok"

        organizations = admin.get(f"{GRAFANA_URL}/api/user/orgs")
        assert organizations.status_code == 200, organizations.text
        assert any(
            organization["orgId"] == 1 and organization["role"] == "Admin"
            for organization in organizations.json()
        )

        datasources = admin.get(f"{GRAFANA_URL}/api/datasources")
        assert datasources.status_code == 200, datasources.text
        provisioned_datasources = {
            datasource["uid"]: datasource["type"]
            for datasource in datasources.json()
        }
        assert provisioned_datasources == EXPECTED_DATASOURCES

        query = admin.post(f"{GRAFANA_URL}/api/ds/query", json={
            "from": str(int((time.time() - 60) * 1000)),
            "to": str(int(time.time() * 1000)),
            "queries": [{
                "refId": "A",
                "datasource": {"uid": "bcd-prometheus", "type": "prometheus"},
                "expr": "vector(1)",
                "format": "time_series",
                "instant": True,
            }],
        })
        assert query.status_code == 200, query.text
        frames = query.json()["results"]["A"]["frames"]
        assert frames and frames[0]["data"]["values"][1][-1] == 1

    with httpx.Client(timeout=30) as invalid:
        invalid.cookies.set(
            "BCD_GRAFANA_SESSION",
            _tamper(cookie),
            domain="localhost",
            path="/",
        )
        rejected = invalid.get(f"{GRAFANA_URL}/api/health")
        assert rejected.status_code == 401, rejected.text


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
    for job in (
        "better-cloud-drive", "prometheus", "loki", "tempo", "otel-collector", "alloy",
        "blackbox-exporter",
    ):
        assert health_by_job.get(job) == "up", f"Prometheus target {job} is not up: {health_by_job}"

    probes = _wait_for_prometheus(
        'probe_success{job="blackbox-http"}',
        lambda samples: len(samples) == 9 and all(float(sample["value"][1]) == 1 for sample in samples),
    )
    assert {sample["metric"]["service"] for sample in probes} == {
        "frontend", "backend", "onlyoffice", "seaweedfs-master", "seaweedfs-filer",
        "seaweedfs-s3", "grafana", "loki", "tempo",
    }

    assert _prometheus_query("bcd_queue_observation_available")

    samples = _wait_for_prometheus(
        "bcd:sli:http_availability:ratio_5m",
        lambda result: bool(result),
    )
    assert 0 <= float(samples[0]["value"][1]) <= 1

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


def test_storage_lifecycle_changes_bounded_metrics(client, auth_headers, created_folder):
    queries = {
        "put": 'sum(bcd_storage_operations_total{operation="put",outcome="success"})',
        "get": 'sum(bcd_storage_operations_total{operation="get",outcome="success"})',
        "delete": 'sum(bcd_storage_operations_total{operation="delete",outcome="success"})',
        "upload_bytes": 'sum(bcd_storage_bytes_total{direction="upload"})',
        "download_bytes": 'sum(bcd_storage_bytes_total{direction="download"})',
    }
    baseline = {name: _metric_total(query) for name, query in queries.items()}

    content = b"observability-storage-lifecycle"
    file_name = f"obs_{uuid.uuid4().hex[:8]}.txt"
    init = client.post("/api/v1/upload/init", json={
        "parentId": created_folder["id"],
        "fileName": file_name,
        "fileSize": len(content),
        "totalChunks": 1,
        "md5Hash": hashlib.md5(content).hexdigest(),
    }, headers=auth_headers)
    assert init.status_code == 200 and init.json()["code"] == 200, init.text
    session_id = init.json()["data"]["sessionId"]
    chunk = client.post(
        f"/api/v1/upload/{session_id}/chunk?chunkNumber=0",
        files={"file": (file_name, content, "text/plain")},
        headers=auth_headers,
    )
    assert chunk.status_code == 200 and chunk.json()["code"] == 200, chunk.text
    complete = client.post(f"/api/v1/upload/{session_id}/complete", headers=auth_headers)
    assert complete.status_code == 200 and complete.json()["code"] == 200, complete.text
    file_id = complete.json()["data"]["fileId"]

    download = client.get(f"/api/v1/download/{file_id}", headers=auth_headers)
    assert download.status_code == 200 and download.content == content
    soft_delete = client.request(
        "DELETE", "/api/v1/files", json={"fileIds": [file_id]}, headers=auth_headers,
    )
    assert soft_delete.status_code == 200 and soft_delete.json()["code"] == 200, soft_delete.text
    permanent_delete = client.delete(f"/api/v1/recycle-bin/{file_id}", headers=auth_headers)
    assert permanent_delete.status_code == 200 and permanent_delete.json()["code"] == 200, permanent_delete.text

    for name, query in queries.items():
        _wait_for_prometheus(
            query,
            lambda samples, previous=baseline[name]: (
                bool(samples) and float(samples[0]["value"][1]) > previous
            ),
        )

    operation_series = _prometheus_query("bcd_storage_operations_total")
    assert {sample["metric"]["operation"] for sample in operation_series} == {
        "put", "get", "delete", "head", "copy",
    }
    assert {sample["metric"]["outcome"] for sample in operation_series} == {"success", "failure"}


def test_dashboards_are_provisioned_and_all_promql_is_valid(client, admin_headers):
    definitions = [
        json.loads(path.read_text(encoding="utf-8"))
        for path in sorted(DASHBOARD_DIR.glob("*.json"))
    ]
    assert {dashboard["uid"]: dashboard["title"] for dashboard in definitions} == EXPECTED_DASHBOARDS

    required_metrics = {
        "bcd_team_quota_used_bytes",
        "bcd_team_quota_limit_bytes",
        "bcd_team_quota_teams",
        "bcd_team_quota_alerts_total",
        "bcd_team_quota_notification_failures_total",
        "bcd_storage_operations_total",
        "bcd_storage_operation_duration_seconds_count",
        "bcd_storage_bytes_total",
        "bcd_observation_last_success_timestamp_seconds",
        "probe_success",
        "ALERTS",
    }
    metric_names = httpx.get(
        "http://localhost:9090/api/v1/label/__name__/values", timeout=5,
    )
    assert metric_names.status_code == 200, metric_names.text
    assert required_metrics.issubset(set(metric_names.json()["data"]))

    for dashboard in definitions:
        assert {link["title"] for link in dashboard["links"]} == {
            "Logs / Loki", "Traces / Tempo", "Observability runbook",
        }
        assert {"Service status"}.issubset({panel["title"] for panel in dashboard["panels"]})
        assert any("Current" in panel["title"] and "alert" in panel["title"] for panel in dashboard["panels"])
        for panel in dashboard["panels"]:
            assert panel["datasource"]["uid"] == "bcd-prometheus"
            assert panel["fieldConfig"]["defaults"].get("noValue") is not None
            for target in panel.get("targets", []):
                expression = target.get("expr")
                if not expression:
                    continue
                response = httpx.get(
                    "http://localhost:9090/api/v1/query",
                    params={"query": expression},
                    timeout=5,
                )
                assert response.status_code == 200, (
                    f"{dashboard['uid']} / {panel['title']}: {response.text}"
                )
                assert response.json()["status"] == "success"

    session = client.post("/api/v1/admin/grafana/session", headers=admin_headers)
    assert session.status_code == 200 and session.json()["code"] == 200, session.text
    deadline = time.time() + 45
    provisioned = {}
    search = None
    while time.time() < deadline:
        search = client.get(
            "http://localhost:3000/grafana/api/search",
            params={"tag": "betterclouddrive"},
        )
        assert search.status_code == 200, search.text
        provisioned = {
            item["uid"]: item["title"]
            for item in search.json()
            if item.get("type") == "dash-db"
        }
        if provisioned == EXPECTED_DASHBOARDS:
            break
        time.sleep(1)
    assert provisioned == EXPECTED_DASHBOARDS

    for uid in EXPECTED_DASHBOARDS:
        loaded = client.get(f"http://localhost:3000/grafana/api/dashboards/uid/{uid}")
        assert loaded.status_code == 200, loaded.text
        assert loaded.json()["dashboard"]["uid"] == uid


def test_alertmanager_delivers_a_development_notification_to_mailpit():
    alert_name = f"ObservabilityAcceptance{uuid.uuid4().hex}"
    starts_at = datetime.now(timezone.utc)
    payload = [{
        "labels": {"alertname": alert_name, "severity": "warning"},
        "annotations": {"summary": "BetterCloudDrive observability acceptance alert"},
        "startsAt": starts_at.isoformat(),
        "endsAt": (starts_at + timedelta(minutes=2)).isoformat(),
    }]
    posted = httpx.post("http://localhost:9093/api/v2/alerts", json=payload, timeout=5)
    assert posted.status_code == 200, posted.text

    deadline = time.time() + 30
    delivered = False
    while time.time() < deadline:
        messages = httpx.get("http://localhost:8025/api/v1/messages", timeout=5)
        assert messages.status_code == 200, messages.text
        items = messages.json().get("messages", [])
        if any(alert_name in (item.get("Subject") or "") for item in items):
            delivered = True
            break
        time.sleep(1)
    assert delivered, f"Alertmanager did not deliver {alert_name} to Mailpit"

    resolved = [{**payload[0], "endsAt": datetime.now(timezone.utc).isoformat()}]
    assert httpx.post("http://localhost:9093/api/v2/alerts", json=resolved, timeout=5).status_code == 200
