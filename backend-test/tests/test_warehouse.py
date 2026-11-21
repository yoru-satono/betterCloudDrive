"""Real business actions -> durable events -> dbt publication -> admin reports."""

from datetime import datetime
import hashlib
import logging
from pathlib import Path
import subprocess
import time
from uuid import uuid4
from zoneinfo import ZoneInfo

import pytest

pytestmark = pytest.mark.warehouse
ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.diagnostics.yml",
           "-f", "docker-compose.warehouse.yml"]


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
    # Public share URLs are capabilities; transport logs must not put them in reports.
    caplog.set_level(logging.WARNING, logger="httpx")


def ok(response):
    assert response.status_code == 200
    envelope = response.json()
    assert envelope["code"] == 200
    return envelope["data"]


def process_dag(job="hourly", conf=None):
    import json
    dag = "bcd_warehouse_" + job
    run_id = "warehouse_acceptance_" + uuid4().hex
    command = COMPOSE + ["exec", "-T", "warehouse-airflow-scheduler", "airflow", "dags"]
    trigger = command + ["trigger", dag, "--run-id", run_id, "--output", "json"]
    if conf is not None:
        trigger.extend(["--conf", json.dumps(conf)])
    completed = subprocess.run(trigger, cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert completed.returncode == 0, "Unable to trigger the warehouse DAG; inspect local Airflow metadata"
    # Let the live scheduler own the task. `dags test` also claims tasks locally
    # and can race a running Airflow 3 scheduler with TaskAlreadyRunningError.
    deadline = time.monotonic() + 240
    while True:
        observed = subprocess.run(command + ["state", dag, run_id], cwd=ROOT,
                                  capture_output=True, text=True, timeout=30)
        assert observed.returncode == 0, "Unable to observe this warehouse DAG run"
        # Airflow appends the run conf to this line for parameterized runs.
        state = observed.stdout.strip().splitlines()[-1].split(",", 1)[0].strip()
        assert state in {"queued", "running", "success", "failed"}, "Unrecognized Airflow run state"
        if state == "success":
            return
        assert state != "failed", "Warehouse DAG failed; inspect its local task/run records"
        assert time.monotonic() < deadline, "Warehouse DAG did not finish before the acceptance deadline"
        time.sleep(0.5)


def process_hourly():
    process_dag()


def test_team_actions_reach_admin_reports_without_duplicate_uploads(client, auth_headers, admin_headers, run_id):
    team = ok(client.post("/api/v1/teams", json={"name": f"warehouse_{run_id}_{uuid4().hex[:6]}"}, headers=auth_headers))
    team_id = team["id"]
    content = ("warehouse-" + uuid4().hex).encode()
    digest = hashlib.md5(content).hexdigest()
    base = f"/api/v1/teams/{team_id}"
    upload = ok(client.post(base + "/upload/init", json={"fileName": "warehouse-test.bin", "fileSize": len(content), "md5Hash": digest}, headers=auth_headers))
    if upload["mode"] == "INSTANT":
        file_id = upload["fileId"]
    else:
        session = upload["sessionId"]
        ok(client.post(base + f"/upload/{session}/chunk", params={"chunkNumber": 0, "chunkMd5": digest},
                       files={"file": ("warehouse-test.bin", content)}, headers=auth_headers))
        file_id = ok(client.post(base + f"/upload/{session}/complete", headers=auth_headers))["fileId"]
        assert ok(client.post(base + f"/upload/{session}/complete", headers=auth_headers))["fileId"] == file_id
    download = client.get(base + f"/download/{file_id}", headers={**auth_headers, "Range": "bytes=1-3"})
    assert download.status_code == 206
    assert hashlib.sha256(download.content).hexdigest() == hashlib.sha256(content[1:4]).hexdigest()
    share = ok(client.post(base + "/shares", json={"fileId": file_id}, headers=auth_headers))
    ok(client.post(f"/api/v1/shares/access/{share['shareCode']}", json={}))
    process_hourly()
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
    params = {"start": today, "end": today, "scope": "TEAM", "teamId": team_id}
    report = ok(client.get("/api/v1/admin/warehouse/overview", params=params, headers=admin_headers))
    usage = report["usage"]
    assert report["meta"]["status"] == "READY"
    assert usage["uploads"] == 1
    assert usage["uploaded_logical_bytes"] == len(content)
    assert usage["active_users"] == 1
    assert usage["active_teams"] == 1
    assert usage["download_attempts"] == 1
    assert usage["download_succeeded"] == 1
    assert usage["download_written_bytes"] == 3
    assert usage["shares_created"] == 1
    assert usage["share_accesses"] == 1
    params["generationId"] = report["meta"]["generationId"]
    trend = ok(client.get("/api/v1/admin/warehouse/trends", params=params, headers=admin_headers))
    assert trend["meta"]["generationId"] == report["meta"]["generationId"]
    assert trend["days"][0]["usage"]["uploads"] == 1
    teams = ok(client.get("/api/v1/admin/warehouse/teams", params=params, headers=admin_headers))["teams"]
    row = next(row for row in teams["records"] if row["team_id"] == team_id)
    assert row["uploads"] == row["download_attempts"] == row["active_users"] == 1
    assert row["uploaded_logical_bytes"] == len(content)
    assert row["download_written_bytes"] == 3
    # A successful source export must not make a repeat rebuild double-count.
    process_hourly()
    repeated = ok(client.get("/api/v1/admin/warehouse/overview", params={key: value for key, value in params.items() if key != "generationId"}, headers=admin_headers))
    assert repeated["usage"] == usage


@pytest.mark.parametrize("endpoint", ["overview", "trends", "teams", "runs"])
def test_reports_are_system_admin_only(client, auth_headers, endpoint):
    response = client.get(f"/api/v1/admin/warehouse/{endpoint}", headers=auth_headers)
    assert response.status_code == 403


def test_invalid_report_ranges_are_rejected(client, admin_headers):
    response = client.get("/api/v1/admin/warehouse/overview", params={"start": "2026-11-20", "end": "2026-11-19"}, headers=admin_headers)
    assert response.json()["code"] == 400


def test_uncollected_history_is_not_reported_as_zero_usage(client, admin_headers):
    from datetime import date, timedelta
    current = ok(client.get("/api/v1/admin/warehouse/overview", headers=admin_headers))
    missing = (date.fromisoformat(current["meta"]["coverageStart"]) - timedelta(days=1)).isoformat()
    params = {"start": missing, "end": missing, "scope": "ALL"}
    report = ok(client.get("/api/v1/admin/warehouse/overview", params=params, headers=admin_headers))
    assert report["usage"] == report["capacity"] == {}
    trend = ok(client.get("/api/v1/admin/warehouse/trends", params=params, headers=admin_headers))
    assert trend["days"][0]["usage"] is None and trend["days"][0]["capacity"] is None


def source_transfers(file_id):
    """Read only typed counters for this test's exact resource, never source payloads."""
    import json
    sql = f"""SELECT coalesce(json_agg(fact), '[]'::json) FROM (
        SELECT channel, mode, outcome, count(*) AS count, sum(byte_count) AS bytes
        FROM warehouse_events WHERE resource_id = {int(file_id)}
            AND event_type = 'DOWNLOAD_FINISHED'
        GROUP BY channel, mode, outcome) AS fact;"""
    result = subprocess.run(COMPOSE + ["exec", "-T", "postgres", "sh", "-c",
        'exec psql -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], input=sql,
        cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, "Unable to inspect this test's warehouse counters"
    return {(row["channel"], row["mode"], row["outcome"]): (row["count"], row["bytes"]) for row in json.loads(result.stdout)}


def test_personal_share_version_zip_and_webdav_transfers_are_captured(client, auth_headers, versioned_file, created_folder, dav, run_id):
    file_id = versioned_file["fileId"]
    size = len(versioned_file["content"])
    assert client.get(f"/api/v1/download/{file_id}", headers=auth_headers).status_code == 200
    version = ok(client.get(f"/api/v1/files/{file_id}/versions", headers=auth_headers))[0]["versionNumber"]
    assert client.get(f"/api/v1/files/{file_id}/versions/{version}/download", headers=auth_headers).status_code == 200
    share = ok(client.post("/api/v1/shares", json={"fileId": file_id}, headers=auth_headers))
    shared = client.post(f"/api/v1/shares/access/{share['shareCode']}/download/{file_id}", json={})
    assert shared.status_code == 200
    # Preview and HEAD read the object but must not add download attempts.
    assert client.get(f"/api/v1/preview/{file_id}", headers=auth_headers).status_code == 200
    assert client.head(f"/api/v1/download/{file_id}", headers=auth_headers).status_code == 200
    folder_id = created_folder["id"]
    zipped = client.get(f"/api/v1/download/folders/{folder_id}/zip", headers=auth_headers)
    assert zipped.status_code == 200 and zipped.content[:2] == b"PK"
    dav_path = f"/webdav/warehouse_{run_id}_{uuid4().hex[:6]}.txt"
    assert dav.put(dav_path, content=b"warehouse-webdav").status_code == 201
    assert dav.head(dav_path).status_code == 200
    assert dav.get(dav_path, headers={"Range": "bytes=0-2"}).status_code == 206
    listing = ok(client.get("/api/v1/files", headers=auth_headers))
    dav_file = next(row for row in listing["records"] if row["fileName"] == dav_path.rsplit("/", 1)[1])
    assert source_transfers(dav_file["id"]) == {("WEBDAV", "FILE", "SUCCEEDED"): (1, 3)}
    assert source_transfers(file_id) == {
        ("API", "FILE", "SUCCEEDED"): (1, size),
        ("API", "VERSION", "SUCCEEDED"): (1, size),
        ("API", "SHARE", "SUCCEEDED"): (1, size),
    }
    assert source_transfers(folder_id) == {("API", "ZIP", "SUCCEEDED"): (1, len(zipped.content))}


def test_warehouse_outage_preserves_core_upload_and_download(client, auth_headers, admin_headers, created_folder, run_id):
    from tests.conftest import _chunked_upload
    stopped = subprocess.run(COMPOSE + ["stop", "clickhouse"], cwd=ROOT, capture_output=True, timeout=30)
    assert stopped.returncode == 0, "Unable to stop the local test warehouse"
    try:
        unavailable = client.get("/api/v1/admin/warehouse/overview", headers=admin_headers)
        assert unavailable.status_code == 503
        assert unavailable.json()["messageKey"] == "error.warehouseUnavailable"
        content = ("outage-" + uuid4().hex).encode()
        file_id = _chunked_upload(client, auth_headers, created_folder["id"], f"warehouse_{run_id}.txt", content)["fileId"]
        downloaded = client.get(f"/api/v1/download/{file_id}", headers=auth_headers)
        assert downloaded.status_code == 200
        assert hashlib.sha256(downloaded.content).digest() == hashlib.sha256(content).digest()
    finally:
        restarted = subprocess.run(COMPOSE + ["start", "clickhouse"], cwd=ROOT, capture_output=True, timeout=30)
        assert restarted.returncode == 0, "Unable to restart the local test warehouse"
        deadline = time.monotonic() + 60
        while True:
            probe = subprocess.run(COMPOSE + ["exec", "-T", "clickhouse", "wget", "--spider", "-q", "http://localhost:8123/ping"],
                                   cwd=ROOT, capture_output=True, timeout=10)
            if probe.returncode == 0:
                break
            assert time.monotonic() < deadline, "Warehouse did not recover after the controlled outage"
            time.sleep(0.25)
    # HTTP /ping can be ready before an authenticated report query (or a stale
    # pooled connection has been discarded). Observe report readiness explicitly.
    deadline = time.monotonic() + 30
    while True:
        recovered = client.get("/api/v1/admin/warehouse/overview", headers=admin_headers)
        if recovered.status_code == 200:
            break
        assert recovered.status_code == 503
        assert time.monotonic() < deadline, "Authenticated reporting did not recover"
        time.sleep(0.25)
    stable = client.get("/api/v1/admin/warehouse/overview", headers=admin_headers)
    assert stable.status_code == 200


def test_team_version_and_authenticated_and_shared_zip_capture(client, auth_headers, test_user, sample_file, run_id):
    from tests.test_teams import create_team_folder, upload_team_file
    from tests.test_stage19_team_recovery_versions import _seed_team_versions
    team = ok(client.post("/api/v1/teams", json={"name": f"warehouse_zip_{run_id}_{uuid4().hex[:6]}"}, headers=auth_headers))
    team_id = team["id"]
    base = f"/api/v1/teams/{team_id}"
    folder = create_team_folder(client, auth_headers, team_id)
    file = upload_team_file(client, auth_headers, team_id, folder["id"], sample_file)
    file_id = file["fileId"]
    _seed_team_versions(file_id, test_user["userId"])
    version = client.get(base + f"/files/{file_id}/versions/1/download", headers={**auth_headers, "Range": "bytes=1-3"})
    assert version.status_code == 206
    assert source_transfers(file_id) == {("API", "VERSION", "SUCCEEDED"): (1, 3)}
    zipped = client.get(base + f"/download/folders/{folder['id']}/zip", headers=auth_headers)
    assert zipped.status_code == 200 and zipped.content[:2] == b"PK"
    share = ok(client.post(base + "/shares", json={"fileId": folder["id"]}, headers=auth_headers))
    public_zip = client.post(f"/api/v1/shares/access/{share['shareCode']}/download/{folder['id']}/zip", json={})
    assert public_zip.status_code == 200 and public_zip.content[:2] == b"PK"
    assert source_transfers(folder["id"]) == {("API", "ZIP", "SUCCEEDED"): (2, len(zipped.content) + len(public_zip.content))}


def test_daily_and_backfill_dags_preserve_real_sample_and_repeatable_metrics(client, admin_headers):
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date().isoformat()
    params = {"start": today, "end": today, "scope": "ALL"}
    before = ok(client.get("/api/v1/admin/warehouse/overview", params=params, headers=admin_headers))
    process_dag("snapshot")
    daily = ok(client.get("/api/v1/admin/warehouse/overview", params=params, headers=admin_headers))
    assert daily["capacity"]["sampled_at"] == before["capacity"]["sampled_at"]
    process_dag("backfill", {"start": today, "end": today})
    rebuilt = ok(client.get("/api/v1/admin/warehouse/overview", params=params, headers=admin_headers))
    assert rebuilt["meta"]["generationId"] != daily["meta"]["generationId"]
    assert rebuilt["capacity"]["sampled_at"] == daily["capacity"]["sampled_at"]
    assert rebuilt["usage"] == daily["usage"]


def test_runtime_dag_startup_schedule_and_timeouts():
    script = """
import runpy
from datetime import datetime, timezone
from airflow.timetables.trigger import CronTriggerTimetable
from airflow.timetables.base import TimeRestriction
module = runpy.run_path('/opt/airflow/dags/bcd_warehouse.py')
dag = module['bcd_warehouse_hourly']
timetable = CronTriggerTimetable(dag.schedule, timezone=str(dag.timezone),
                                run_immediately=dag.timetable.run_immediately)
restriction = TimeRestriction(earliest=dag.start_date, latest=None, catchup=dag.catchup)
now = datetime.now(timezone.utc)
first = timetable.next_dagrun_info_v2(last_dagrun_info=None, restriction=restriction)
assert first is not None and first.run_after <= now
following = timetable.next_dagrun_info_v2(last_dagrun_info=first, restriction=restriction)
assert following is not None and following.run_after > now
assert following.run_after.minute == 10
for current in (dag, module['bcd_warehouse_snapshot'], module['backfill']):
    assert current.max_active_runs == 1
    assert current.dagrun_timeout.total_seconds() == 2700
    assert all(task.execution_timeout.total_seconds() == 1200 for task in current.tasks)
daily = module['bcd_warehouse_snapshot']
assert daily.schedule == '5 0 * * *' and str(daily.timezone) == 'Asia/Shanghai'
assert module['backfill'].schedule is None
print('Runtime scheduling and timeout contracts passed')
"""
    completed = subprocess.run(COMPOSE + ["exec", "-T", "warehouse-airflow-scheduler", "python", "-c", script],
                               cwd=ROOT, capture_output=True, text=True, timeout=30)
    assert completed.returncode == 0, "Airflow runtime scheduling or timeout contract failed"
