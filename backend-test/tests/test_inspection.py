"""Admin inspection contracts against the real application, database and object store."""

import logging
import json
from pathlib import Path
import subprocess
import time
from uuid import UUID, uuid4

import pytest

pytestmark = pytest.mark.inspection
ROOT = Path(__file__).resolve().parents[2]
BASE = "/api/v1/admin/storage-inspections"


class PrivateHeaders(dict):
    def __repr__(self):
        return "<request headers redacted>"


@pytest.fixture
def auth_headers(auth_headers):
    return PrivateHeaders(auth_headers)


@pytest.fixture
def admin_headers(admin_headers):
    return PrivateHeaders(admin_headers)


@pytest.fixture(autouse=True)
def quiet_transport_logs(caplog):
    caplog.set_level(logging.WARNING, logger="httpx")


def ok(response):
    assert response.status_code == 200
    envelope = response.json()
    assert envelope["code"] == 200
    return envelope["data"]


def sql(statement):
    # The local database container supplies authentication. Never echo env values or SQL errors.
    completed = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
         'exec psql -X -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=statement, cwd=ROOT, capture_output=True, text=True, timeout=30,
    )
    assert completed.returncode == 0, "Controlled inspection fixture SQL failed"
    return completed.stdout.strip()


def finished(client, headers, run):
    deadline = time.monotonic() + 120
    while run["status"] in {"QUEUED", "RUNNING"}:
        assert time.monotonic() < deadline, "Inspection did not finish before the acceptance deadline"
        time.sleep(0.2)
        run = ok(client.get(BASE + f"/runs/{run['id']}", headers=headers))
    return run


def full(client, headers):
    overview = ok(client.get(BASE + "/overview", headers=headers))
    assert overview["accountingReady"] is True, "Run the explicit offline quota cutover before acceptance"
    if overview["latestRun"] and overview["latestRun"]["status"] in {"QUEUED", "RUNNING"}:
        finished(client, headers, overview["latestRun"])
    result = finished(client, headers, ok(client.post(BASE + "/runs", headers=headers)))
    assert result["status"] == "SUCCEEDED"
    assert result["metadataComplete"] is True
    assert result["inventoryComplete"] is True
    return result


@pytest.mark.security
def test_inspection_requires_admin_for_every_route(client, auth_headers):
    identity = str(uuid4())
    routes = [("GET", "/overview"), ("GET", "/runs"), ("GET", f"/runs/{identity}"),
              ("GET", "/findings"), ("GET", f"/findings/{identity}"),
              ("POST", "/runs"), ("POST", f"/runs/{identity}/cancel"),
              ("POST", f"/findings/{identity}/rechecks"),
              ("POST", f"/findings/{identity}/reviews"),
              ("POST", f"/findings/{identity}/repair-preview"), ("POST", "/quota-repairs")]
    for method, path in routes:
        # The existing SecurityFilterChain uses HTTP 403 for an anonymous admin request.
        assert client.request(method, BASE + path).status_code == 403
        assert client.request(method, BASE + path, headers=auth_headers, json={}).status_code == 403


def test_inspection_validates_filters_and_separates_complete_report(client, admin_headers):
    run = full(client, admin_headers)
    overview = ok(client.get(BASE + "/overview", headers=admin_headers))
    assert overview["latestComplete"]["id"] == run["id"]
    assert overview["zone"] == "Asia/Shanghai"
    assert overview["nextScheduledAt"]
    for params in [{"size": 101}, {"page": 0}, {"type": "INVALID"}, {"ownerId": 1},
                   {"scope": "TEAM", "ownerId": -1}, {"status": "IGNORED"}]:
        response = client.get(BASE + "/findings", params=params, headers=admin_headers)
        assert response.status_code == 400
    records = ok(client.get(BASE + "/findings", headers=admin_headers))["records"]
    assert all("objectKey" not in record for record in records)


def test_owned_quota_drift_review_preview_repair_and_replay(client, auth_headers, admin_headers, uploaded_file):
    owner = int(ok(client.get("/api/v1/auth/me", headers=auth_headers))["id"])
    original = int(sql(f"SELECT storage_used FROM users WHERE id={owner};"))
    assert original >= uploaded_file["fileSize"]
    sql(f"UPDATE users SET storage_used=storage_used+17,quota_revision=quota_revision+1 WHERE id={owner};")
    try:
        full(client, admin_headers)
        params = {"type": "QUOTA_DRIFT", "scope": "PERSONAL", "ownerId": owner}
        findings = ok(client.get(BASE + "/findings", params=params, headers=admin_headers))["records"]
        assert len(findings) == 1
        finding = findings[0]
        identity = str(UUID(finding["id"]))
        assert finding["status"] == "SUSPECTED"
        assert finding["observedBytes"] - finding["expectedBytes"] == 17
        assert client.post(BASE + f"/findings/{identity}/repair-preview", headers=admin_headers).status_code == 409
        note = {"action": "IGNORE", "reason": "controlled acceptance fixture"}
        ok(client.post(BASE + f"/findings/{identity}/reviews", json=note, headers=admin_headers))
        # Real 10-minute windows are tested with an injected clock in InspectionWorkerIT.
        # Backdate only this fixture's evidence to exercise the real HTTP repair path quickly.
        sql(f"UPDATE storage_inspection_findings SET stable_since=now()-INTERVAL '11 minutes',"
            f"last_counted_at=now()-INTERVAL '11 minutes' WHERE id='{identity}' AND owner_id={owner};")
        recheck = ok(client.post(BASE + f"/findings/{identity}/rechecks", headers=admin_headers))
        assert finished(client, admin_headers, recheck)["status"] == "SUCCEEDED"
        detail = ok(client.get(BASE + f"/findings/{identity}", headers=admin_headers))
        assert detail["finding"]["status"] == "CONFIRMED"
        assert detail["finding"]["reviewStatus"] == "IGNORED"
        assert len(detail["reviews"]) == 1
        preview = ok(client.post(BASE + f"/findings/{identity}/repair-preview", headers=admin_headers))
        assert preview["recordedBytes"] == original + 17
        assert preview["expectedBytes"] == original
        payload = {"previewId": preview["id"], "idempotencyKey": str(uuid4()), "reason": "controlled fixture repair"}
        repaired = ok(client.post(BASE + "/quota-repairs", json=payload, headers=admin_headers))
        repeated = ok(client.post(BASE + "/quota-repairs", json=payload, headers=admin_headers))
        assert repeated["id"] == repaired["id"]
        assert repaired["usedAfter"] == original
        payload["reason"] = "different request"
        assert client.post(BASE + "/quota-repairs", json=payload, headers=admin_headers).status_code == 409
        detail = ok(client.get(BASE + f"/findings/{identity}", headers=admin_headers))
        assert detail["finding"]["status"] == "RESOLVED"
        assert len(detail["repairs"]) == 1
    finally:
        # The fixture owner is unique to this test run; preserve all other users and counters.
        sql(f"UPDATE users SET storage_used={original},quota_revision=quota_revision+1 WHERE id={owner} AND storage_used={original + 17};")


def test_live_missing_content_and_thumbnail_resolve_after_reference_recovery(client, auth_headers, admin_headers, uploaded_file):
    owner = int(ok(client.get("/api/v1/auth/me", headers=auth_headers))["id"])
    file_id = int(uploaded_file["fileId"])
    original = json.loads(sql(f"SELECT json_build_object('key',storage_path,'thumbnail',thumbnail_path)::text FROM files WHERE id={file_id} AND user_id={owner} AND team_id IS NULL;"))
    marker = uuid4().hex
    sql(f"UPDATE files SET storage_path='users/{owner}/inspection-missing-{marker}',thumbnail_path='thumbnails/inspection-missing-{marker}' WHERE id={file_id} AND user_id={owner} AND team_id IS NULL;")

    def restore():
        key = original["key"].replace("'", "''")
        thumbnail = "NULL" if original["thumbnail"] is None else "'" + original["thumbnail"].replace("'", "''") + "'"
        sql(f"UPDATE files SET storage_path='{key}',thumbnail_path={thumbnail} WHERE id={file_id} AND user_id={owner} AND team_id IS NULL;")

    try:
        full(client, admin_headers)
        identities = []
        for kind, reference in [("MISSING_OBJECT", "FILE"), ("AUXILIARY_MISSING", "THUMBNAIL")]:
            params = {"type": kind, "scope": "PERSONAL", "ownerId": owner}
            rows = ok(client.get(BASE + "/findings", params=params, headers=admin_headers))["records"]
            selected = [row for row in rows if row["referenceId"] == str(file_id) and row["status"] != "RESOLVED"]
            assert len(selected) == 1
            assert selected[0]["referenceKind"] == reference
            assert selected[0]["status"] == "SUSPECTED"
            identities.append(selected[0]["id"])
        restore()
        for identity in identities:
            run = ok(client.post(BASE + f"/findings/{identity}/rechecks", headers=admin_headers))
            assert finished(client, admin_headers, run)["status"] == "SUCCEEDED"
            detail = ok(client.get(BASE + f"/findings/{identity}", headers=admin_headers))
            assert detail["finding"]["status"] == "RESOLVED"
    finally:
        restore()
