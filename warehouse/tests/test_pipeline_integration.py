from datetime import datetime, timezone
from uuid import uuid4

import pytest

from bcd_warehouse.ingest import capture_snapshot, export_events
from bcd_warehouse.pipeline import run, WarehouseJobError, LOCK_ID
from conftest import insert_event

pytestmark = pytest.mark.integration


def test_retry_after_insert_before_ack_is_deduplicated_at_query_time(warehouse):
    source, client, settings = warehouse
    insert_event(source, "UPLOAD_COMMITTED", size=7, mode="INSTANT")

    class BrokenSink:
        def insert(self, *arguments, **keywords):
            client.insert(*arguments, **keywords)
            raise RuntimeError("controlled_ack_boundary_failure")

    with pytest.raises(RuntimeError, match="controlled_ack_boundary_failure"):
        export_events(source, BrokenSink())
    assert source.execute("SELECT count(*) FROM warehouse_events WHERE exported_at IS NULL").fetchone()[0] == 1
    source.execute("UPDATE warehouse_events SET export_lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second'")
    assert export_events(source, client) == 1
    assert client.query("SELECT count() FROM ods_events").result_rows[0][0] == 2
    run("hourly", settings)
    assert client.query("SELECT count() FROM dwd_events").result_rows[0][0] == 1
    assert client.query("SELECT uploads, uploaded_logical_bytes, instant_uploads FROM ads_usage_daily WHERE scope_type='ALL'").result_rows == [(1, 7, 1)]


def test_real_pipeline_capacity_scopes_unknown_downloads_and_repeatability(warehouse):
    source, client, settings = warehouse
    insert_event(source, "LOGIN_SUCCEEDED")
    insert_event(source, "UPLOAD_COMMITTED", team=1, size=11, mode="MULTIPART")
    completed, unknown = uuid4(), uuid4()
    insert_event(source, "DOWNLOAD_STARTED", attempt=completed, mode="VERSION", outcome="STARTED")
    insert_event(source, "DOWNLOAD_FINISHED", attempt=completed, mode="VERSION", size=5)
    insert_event(source, "DOWNLOAD_STARTED", actor=None, team=1, attempt=unknown, mode="SHARE", outcome="STARTED")
    insert_event(source, "SHARE_ACCESSED", actor=None)
    first = run("hourly", settings)
    capacity = client.query("""
        SELECT current_bytes, trash_bytes, version_bytes, file_count, folder_count
        FROM ads_capacity_daily WHERE scope_type='ALL'
        """).result_rows
    assert capacity == [(18, 13, 17, 2, 0)]
    metrics = client.query("""
        SELECT active_users, active_teams, uploads, download_attempts, download_succeeded,
            download_unknown, download_written_bytes, share_accesses
        FROM ads_usage_daily WHERE scope_type='ALL'
        """).result_rows
    assert metrics == [(1, 1, 1, 2, 1, 1, 5, 1)]
    second = run("hourly", settings)
    assert first["runId"] != second["runId"]
    assert client.query("SELECT count() FROM ods_snapshot_batches").result_rows[0][0] == 1
    assert client.query("""
        SELECT active_users, active_teams, uploads, download_attempts, download_succeeded,
            download_unknown, download_written_bytes, share_accesses
        FROM ads_usage_daily WHERE scope_type='ALL'
            AND generation_id = (SELECT generation_id FROM publications ORDER BY published_at DESC LIMIT 1)
        """).result_rows == metrics


def test_failed_quality_check_keeps_previous_generation_published(warehouse):
    source, client, settings = warehouse
    run("hourly", settings)
    published = client.query("SELECT generation_id FROM publications").result_rows
    insert_event(source, "DOWNLOAD_FINISHED", attempt=uuid4(), size=3, mode="FILE")
    with pytest.raises(WarehouseJobError, match="dbt_validation_failed"):
        run("hourly", settings)
    assert client.query("SELECT generation_id FROM publications").result_rows == published


def test_expired_start_on_oldest_partial_raw_day_does_not_block_publication(warehouse):
    from datetime import timedelta
    from zoneinfo import ZoneInfo
    source, client, settings = warehouse
    now = datetime.now(timezone.utc)
    first_full_day = (now.astimezone(ZoneInfo("Asia/Shanghai")).replace(hour=0, minute=0, second=0, microsecond=0)
                      - timedelta(days=89)).astimezone(timezone.utc)
    raw_cutoff = now - timedelta(days=90)
    terminal_time = raw_cutoff + (first_full_day - raw_cutoff) / 2
    identity = insert_event(source, "DOWNLOAD_FINISHED", attempt=uuid4(), size=7, mode="FILE")
    # A transfer's older START has already expired. This remaining terminal is
    # retained by the raw timestamp TTL, but belongs to no date being rebuilt.
    source.execute("UPDATE warehouse_events SET occurred_at=%s WHERE event_id=%s", (terminal_time, identity))
    result = run("hourly", settings)
    assert result["status"] == "SUCCEEDED"
    assert client.query("""
        SELECT sum(download_attempts) FROM ads_usage_daily
        WHERE generation_id={generation:UUID} AND scope_type='ALL'
        """, parameters={"generation": result["runId"]}).first_row[0] == 0


def test_current_snapshot_is_immutable_within_business_day(warehouse):
    source, client, settings = warehouse
    first = capture_snapshot(source, client)
    second = capture_snapshot(source, client)
    assert first == second
    assert client.query("SELECT count() FROM ods_snapshot_batches").result_rows[0][0] == 1


def test_orphan_run_recovery_requires_lock_and_respects_publication(warehouse):
    from datetime import timedelta
    source, client, settings = warehouse
    now = datetime.now(timezone.utc)
    orphan, published = uuid4(), uuid4()
    for identity in (orphan, published):
        client.insert("pipeline_runs", [[identity, "hourly", "RUNNING", now - timedelta(hours=1), now - timedelta(hours=1), ""]],
                      column_names=["run_id", "job", "status", "started_at", "updated_at", "error_key"])
    client.insert("publications", [[published, now, now]], column_names=["generation_id", "published_at", "data_through"])
    source.execute("SELECT pg_advisory_lock(%s)", (LOCK_ID,))
    try:
        with pytest.raises(WarehouseJobError, match="pipeline_already_running"):
            run("hourly", settings)
        assert client.query("SELECT count() FROM pipeline_runs").first_row[0] == 2
    finally:
        source.execute("SELECT pg_advisory_unlock(%s)", (LOCK_ID,))
    run("hourly", settings)
    rows = client.query("""
        SELECT run_id, argMax(tuple(status, error_key), updated_at) FROM pipeline_runs
        WHERE run_id IN ({orphan:UUID}, {published:UUID}) GROUP BY run_id
        """, parameters={"orphan": orphan, "published": published}).result_rows
    assert dict(rows) == {orphan: ("FAILED", "pipeline_interrupted"), published: ("SUCCEEDED", "")}
    run("hourly", settings)
    assert client.query("SELECT count() FROM pipeline_runs WHERE run_id IN ({orphan:UUID}, {published:UUID})",
                        parameters={"orphan": orphan, "published": published}).first_row[0] == 4


def test_lost_source_lock_during_build_cannot_publish(warehouse, monkeypatch):
    from bcd_warehouse import pipeline
    source, client, settings = warehouse
    run("hourly", settings)
    published = client.query("SELECT generation_id FROM publications").result_rows
    build = pipeline.run_dbt

    def disconnect_owner_after_build(generation):
        build(generation)
        owner = source.execute("SELECT pid FROM pg_locks WHERE locktype='advisory' AND classid=0 AND objid=%s AND granted",
                               (LOCK_ID,)).fetchone()
        assert owner is not None
        source.execute("SELECT pg_terminate_backend(%s)", (owner[0],))

    monkeypatch.setattr(pipeline, "run_dbt", disconnect_owner_after_build)
    with pytest.raises(WarehouseJobError, match="AdminShutdown|OperationalError"):
        run("hourly", settings)
    assert client.query("SELECT generation_id FROM publications").result_rows == published
    assert client.query("SELECT status FROM pipeline_runs ORDER BY updated_at DESC LIMIT 1").first_row[0] == "FAILED"


def test_empty_completed_snapshot_produces_known_zero_scopes(warehouse):
    source, client, settings = warehouse
    from zoneinfo import ZoneInfo
    now = datetime.now(timezone.utc)
    client.insert("ods_snapshot_batches", [[uuid4(), now.astimezone(ZoneInfo("Asia/Shanghai")).date(), now, 0]],
                  column_names=["batch_id", "business_date", "captured_at", "record_count"])
    run("hourly", settings)
    assert client.query("SELECT scope_type, current_bytes, file_count FROM ads_capacity_daily ORDER BY scope_type").result_rows == [
        ("ALL", 0, 0), ("PERSONAL", 0, 0), ("TEAM", 0, 0),
    ]


def test_rebuild_keeps_published_aggregates_after_raw_retention(warehouse):
    source, client, settings = warehouse
    insert_event(source, "UPLOAD_COMMITTED", size=7, mode="INSTANT")
    initial = run("hourly", settings)["runId"]
    # Simulate an already published historical day whose raw records have expired.
    # Only the aggregate is available; a rebuild must carry it forward unchanged.
    for table in ("ads_usage_daily", "ads_capacity_daily"):
        client.command(f"""
            INSERT INTO {table}
            SELECT * REPLACE (day - 100 AS day) FROM {table}
            WHERE generation_id = {{generation:UUID}} AND scope_type = 'ALL'
            """, parameters={"generation": initial})
    latest = run("hourly", settings)["runId"]
    assert client.query("""
        SELECT uploads, uploaded_logical_bytes FROM ads_usage_daily
        WHERE generation_id = {generation:UUID} AND scope_type = 'ALL'
            AND day = toDate(now(), 'Asia/Shanghai') - 100
        """, parameters={"generation": latest}).result_rows == [(1, 7)]
    assert client.query("""
        SELECT current_bytes, trash_bytes, version_bytes FROM ads_capacity_daily
        WHERE generation_id = {generation:UUID} AND scope_type = 'ALL'
            AND day = toDate(now(), 'Asia/Shanghai') - 100
        """, parameters={"generation": latest}).result_rows == [(18, 13, 17)]


def test_backfill_rebuilds_retained_facts_without_resampling(warehouse):
    from datetime import timedelta
    from zoneinfo import ZoneInfo
    source, client, settings = warehouse
    today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
    initial = run("hourly", settings)
    samples = client.query("SELECT batch_id, captured_at FROM ods_snapshot_batches").result_rows
    # A delayed event changes its original business day on the next rebuild.
    identity = insert_event(source, "UPLOAD_COMMITTED", size=19, mode="INSTANT")
    source.execute("UPDATE warehouse_events SET occurred_at = occurred_at - INTERVAL '1 hour' WHERE event_id = %s", (identity,))
    result = run("backfill", settings, today, today)
    assert result["runId"] != initial["runId"]
    assert client.query("SELECT batch_id, captured_at FROM ods_snapshot_batches").result_rows == samples

    assert client.query("""
        SELECT sum(uploads), sum(uploaded_logical_bytes) FROM ads_usage_daily
        WHERE generation_id = {generation:UUID} AND scope_type='ALL'
        """, parameters={"generation": result["runId"]}).result_rows == [(1, 19)]
    published = client.query("SELECT generation_id FROM publications ORDER BY published_at").result_rows
    with pytest.raises(WarehouseJobError, match="historical_snapshot_unavailable"):
        run("backfill", settings, today - timedelta(days=1), today)
    assert client.query("SELECT generation_id FROM publications ORDER BY published_at").result_rows == published
    assert client.query("SELECT batch_id, captured_at FROM ods_snapshot_batches").result_rows == samples


def test_snapshot_projections_share_one_read_snapshot_during_concurrent_commit(warehouse):
    import psycopg
    from psycopg.rows import dict_row
    source, client, settings = warehouse
    old_quota = source.execute("SELECT storage_quota FROM users WHERE id=1").fetchone()[0]
    old_size = source.execute("SELECT file_size FROM files WHERE id=1").fetchone()[0]

    class InterleavedCursor:
        def __enter__(self):
            self.cursor = source.cursor(row_factory=dict_row)
            self.cursor.__enter__()
            return self

        def __exit__(self, *arguments):
            return self.cursor.__exit__(*arguments)

        def execute(self, query):
            if query == "SELECT * FROM warehouse_source_teams":
                # Commit between reading users and files. A READ COMMITTED scan
                # would mix old user quota with new file size in the same batch.
                with psycopg.connect(settings.source_dsn) as writer:
                    writer.execute("UPDATE users SET storage_quota=storage_quota+101 WHERE id=1")
                    writer.execute("UPDATE files SET file_size=file_size+101 WHERE id=1")
            return self.cursor.execute(query)

        def fetchone(self):
            return self.cursor.fetchone()

        def fetchall(self):
            return self.cursor.fetchall()

    class InterleavedSource:
        autocommit = True

        def transaction(self):
            return source.transaction()

        def cursor(self, **_):
            return InterleavedCursor()

    try:
        before = datetime.now(timezone.utc)
        batch = capture_snapshot(InterleavedSource(), client)
        after = datetime.now(timezone.utc)
        assert client.query("SELECT size_bytes FROM ods_snapshots WHERE batch_id={batch:UUID} AND record_type='file' AND id=1",
                            parameters={"batch": batch}).result_rows == [(old_size,)]
        assert client.query("SELECT quota_bytes FROM ods_snapshots WHERE batch_id={batch:UUID} AND record_type='user' AND id=1",
                            parameters={"batch": batch}).result_rows == [(old_quota,)]
        captured_at = client.query("SELECT toUnixTimestamp64Milli(captured_at) FROM ods_snapshot_batches WHERE batch_id={batch:UUID}",
                                   parameters={"batch": batch}).first_row[0]
        assert int(before.timestamp() * 1000) <= captured_at <= int(after.timestamp() * 1000)
    finally:
        source.execute("UPDATE users SET storage_quota=%s WHERE id=1", (old_quota,))
        source.execute("UPDATE files SET file_size=%s WHERE id=1", (old_size,))
