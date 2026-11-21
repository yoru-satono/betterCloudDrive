"""Serial extraction, dbt validation and publication of immutable generations."""

from contextlib import contextmanager
from datetime import date, datetime, timedelta, timezone
import json
import os
from pathlib import Path
import subprocess
from uuid import uuid4
from zoneinfo import ZoneInfo

import clickhouse_connect
import psycopg

from .config import Settings
from .ingest import capture_snapshot, export_events

ROOT = Path(__file__).resolve().parents[1]
LOCK_ID = 735190421


class WarehouseJobError(RuntimeError):
    pass


def connect_clickhouse(settings):
    return clickhouse_connect.get_client(
        host=settings.clickhouse_host, port=settings.clickhouse_port,
        username=settings.clickhouse_user, password=settings.clickhouse_password,
        database=settings.clickhouse_database, connect_timeout=5, send_receive_timeout=120,
    )


@contextmanager
def exclusive_pipeline(settings):
    with psycopg.connect(settings.source_dsn, autocommit=True) as source:
        if not source.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_ID,)).fetchone()[0]:
            raise WarehouseJobError("pipeline_already_running")
        # Closing this dedicated session releases its advisory lock on every
        # path, including a broken connection. An explicit unlock could mask
        # the original processing error when PostgreSQL has already restarted.
        yield source


def validate_backfill(start, end, snapshot_days, today=None):
    today = today or datetime.now(ZoneInfo("Asia/Shanghai")).date()
    if start > end or (end - start).days >= 31 or start < today - timedelta(days=89) or end > today:
        raise WarehouseJobError("invalid_backfill_range")
    required = {start + timedelta(days=index) for index in range((end - start).days + 1)}
    if not required.issubset(set(snapshot_days)):
        raise WarehouseJobError("historical_snapshot_unavailable")


def run_dbt(generation):
    # dbt and Airflow run in different virtual environments to prevent dependency conflicts.
    executable = Path(os.sys.executable).with_name("dbt.exe" if os.name == "nt" else "dbt")
    target = Path(os.getenv("WAREHOUSE_DBT_ARTIFACT_ROOT", "/tmp/bcd-warehouse")) / str(generation)
    command = [str(executable), "build", "--project-dir", str(ROOT / "dbt"),
               "--profiles-dir", str(ROOT / "dbt"), "--target-path", str(target / "target"),
               "--log-path", str(target / "logs"), "--vars", json.dumps({"generation_id": str(generation)})]
    completed = subprocess.run(command, check=False, timeout=600, capture_output=True, text=True)
    if completed.returncode:
        # dbt logs remain local artifacts; do not send connection errors or SQL to API/log payloads.
        raise WarehouseJobError("dbt_validation_failed")


def recover_interrupted_runs(clickhouse):
    # Called only after the source advisory lock is acquired. No other live
    # pipeline owns these unfinished records; SIGKILL cannot write its own end.
    # A publication marker is the authority if the last status write was lost.
    clickhouse.command("""
        INSERT INTO pipeline_runs (run_id, job, status, started_at, updated_at, error_key)
        WITH latest AS (
            SELECT run_id, argMax(tuple(job, status, started_at), updated_at) AS fact
            FROM pipeline_runs GROUP BY run_id
        )
        SELECT run_id, fact.1,
            if(run_id IN (SELECT generation_id FROM publications), 'SUCCEEDED', 'FAILED'),
            fact.3, now64(6),
            if(run_id IN (SELECT generation_id FROM publications), '', 'pipeline_interrupted')
        FROM latest WHERE fact.2 = 'RUNNING'
            OR (fact.2 = 'SUCCEEDED' AND fact.3 >= now() - INTERVAL 364 DAY
                AND run_id NOT IN (SELECT generation_id FROM publications))
        """)


def run(job, settings=None, start=None, end=None):
    settings = settings or Settings.from_env()
    if job not in {"hourly", "snapshot", "backfill"}:
        raise WarehouseJobError("invalid_job")
    generation = uuid4()
    started_at = datetime.now(timezone.utc)
    with exclusive_pipeline(settings) as source:
        clickhouse = connect_clickhouse(settings)
        try:
            recover_interrupted_runs(clickhouse)
            clickhouse.insert("pipeline_runs", [[generation, job, "RUNNING", started_at, started_at, ""]],
                              column_names=["run_id", "job", "status", "started_at", "updated_at", "error_key"])
            if job == "backfill":
                days = [row[0] for row in clickhouse.query("SELECT DISTINCT business_date FROM ods_snapshot_batches").result_rows]
                validate_backfill(start, end, days)
            # A bounded loop is not a lossy watermark: leftover events stay pending for the next run.
            # The deadline also ensures a stream of writes cannot hold the pipeline indefinitely.
            imported = 0
            for _ in range(1000):
                count = export_events(source, clickhouse, settings.batch_size)
                imported += count
                if count < settings.batch_size:
                    break
            # The first hourly run establishes a baseline; only the snapshot DAG samples subsequent days.
            has_baseline = clickhouse.query("SELECT count() FROM ods_snapshot_batches").result_rows[0][0] > 0
            if job == "snapshot" or not has_baseline:
                capture_snapshot(source, clickhouse, settings.source_zone)
            run_dbt(generation)
            # If PostgreSQL restarted during dbt, this session lost the lock.
            # Refuse to publish from that disconnected owner after recovery.
            source.execute("SELECT 1")
            published_at = datetime.now(timezone.utc)
            clickhouse.insert("pipeline_runs", [[generation, job, "SUCCEEDED", started_at, published_at, ""]],
                              column_names=["run_id", "job", "status", "started_at", "updated_at", "error_key"])
            clickhouse.insert("publications", [[generation, published_at, started_at]],
                              column_names=["generation_id", "published_at", "data_through"])
            return {"runId": str(generation), "importedEvents": imported, "status": "SUCCEEDED"}
        except Exception as error:
            error_key = str(error) if isinstance(error, WarehouseJobError) else type(error).__name__
            try:
                clickhouse.insert("pipeline_runs", [[generation, job, "FAILED", started_at,
                    datetime.now(timezone.utc), error_key]],
                    column_names=["run_id", "job", "status", "started_at", "updated_at", "error_key"])
            except Exception:
                # Airflow remains the failure record when ClickHouse itself is unreachable.
                error_key = "run_record_unavailable:" + error_key
            raise WarehouseJobError(error_key) from None
        finally:
            clickhouse.close()
