"""Leased event export and immutable consistent metadata snapshots."""

from datetime import datetime, timezone
from uuid import uuid4
from zoneinfo import ZoneInfo

from psycopg.rows import dict_row


EVENT_COLUMNS = (
    "event_id", "event_type", "occurred_at", "actor_user_id", "team_id", "resource_id",
    "attempt_id", "channel", "mode", "outcome", "byte_count", "schema_version",
)
SNAPSHOT_COLUMNS = (
    "batch_id", "captured_at", "record_type", "id", "user_id", "team_id", "parent_id", "kind",
    "status", "is_deleted", "archived", "size_bytes", "quota_bytes", "created_at", "expires_at",
)
PROJECTIONS = ("users", "teams", "members", "files", "versions", "shares")


class DataQualityError(RuntimeError):
    """Raised with stable identifiers, never raw records or connection details."""


def export_events(source, clickhouse, batch_size=1000):
    """Acknowledge only after insertion; crashed leases expire and retry safely.

    The source connection must be autocommit. Each claim/ack is a short transaction;
    a ClickHouse network round trip never holds source row locks.
    """
    if not source.autocommit:
        raise ValueError("Exporter requires an autocommit source connection")
    batch = uuid4()
    with source.transaction(), source.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            WITH claimed AS (
                SELECT event_id FROM warehouse_events
                WHERE exported_at IS NULL
                  AND (export_lease_until IS NULL OR export_lease_until < CURRENT_TIMESTAMP)
                ORDER BY created_at, event_id
                FOR UPDATE SKIP LOCKED LIMIT %s
            )
            UPDATE warehouse_events e
            SET export_batch_id = %s, export_lease_until = CURRENT_TIMESTAMP + INTERVAL '15 minutes'
            FROM claimed c WHERE e.event_id = c.event_id
            RETURNING e.*
            """, (batch_size, batch))
        rows = cursor.fetchall()
    if not rows:
        return 0
    # Intentionally retain leases on failure: another exporter can recover them.
    clickhouse.insert("ods_events", [[row[name] for name in EVENT_COLUMNS] for row in rows],
                      column_names=list(EVENT_COLUMNS))
    with source.transaction():
        source.execute("""
            UPDATE warehouse_events SET exported_at = CURRENT_TIMESTAMP,
                export_lease_until = NULL
            WHERE export_batch_id = %s AND exported_at IS NULL
            """, (batch,))
    return len(rows)


def effective_deleted(files):
    """Apply ancestor deletion, and reject cycles/missing or cross-space parents."""
    indexed = {row["id"]: row for row in files}
    if len(indexed) != len(files):
        raise DataQualityError("duplicate_file_identity")
    resolved = {}
    for identity in indexed:
        cursor = identity
        chain, seen = [], set()
        while cursor is not None and cursor not in resolved:
            if cursor in seen:
                raise DataQualityError("file_ancestor_cycle")
            row = indexed.get(cursor)
            if row is None:
                raise DataQualityError("file_parent_missing")
            seen.add(cursor)
            chain.append(cursor)
            parent = indexed.get(row["parent_id"])
            if parent is not None:
                if parent["file_type"] != "folder" or parent["team_id"] != row["team_id"]:
                    raise DataQualityError("file_parent_scope_mismatch")
                if row["team_id"] is None and parent["user_id"] != row["user_id"]:
                    raise DataQualityError("file_parent_scope_mismatch")
            cursor = row["parent_id"]
        deleted = resolved.get(cursor, False)
        for node in reversed(chain):
            deleted = deleted or bool(indexed[node]["is_deleted"])
            resolved[node] = deleted
    return resolved


def source_instant(value, source_zone):
    if value is None:
        return None
    if value.tzinfo is None:
        value = value.replace(tzinfo=ZoneInfo(source_zone))
    return value.astimezone(timezone.utc)


def normalize_snapshot(records, batch, captured_at, source_zone):
    deleted = effective_deleted(records["files"])
    result = []
    for projection in PROJECTIONS:
        for row in records[projection]:
            record_type = {"users": "user", "teams": "team", "members": "member",
                           "files": "file", "versions": "version", "shares": "share"}[projection]
            identity = row.get("id", row.get("user_id"))
            kind = row.get("file_type", row.get("role", ""))
            is_deleted = bool(row.get("deleted_at")) or bool(row.get("is_canceled"))
            if projection == "files":
                is_deleted = deleted[identity]
            size = row.get("file_size") or 0
            quota = row.get("storage_quota") or 0
            if size < 0 or quota < 0:
                raise DataQualityError("negative_size_or_quota")
            result.append([
                batch, captured_at, record_type, identity,
                row.get("user_id", identity if projection == "users" else None),
                row.get("team_id", identity if projection == "teams" else None),
                row.get("parent_id"), kind, row.get("status", 1), int(is_deleted),
                int(bool(row.get("archived_at"))), size, quota,
                source_instant(row.get("created_at"), source_zone),
                source_instant(row.get("expire_at"), source_zone),
            ])
    return result


def capture_snapshot(source, clickhouse, source_zone="UTC", now=None):
    """One immutable sample per business date; reprocessing never resamples history."""
    if not source.autocommit:
        raise ValueError("Snapshot requires an autocommit source connection")
    sampled_at = now or datetime.now(timezone.utc)
    business_date = sampled_at.astimezone(ZoneInfo("Asia/Shanghai")).date()
    prior = clickhouse.query("""
        SELECT batch_id FROM ods_snapshot_batches
        WHERE business_date = {day:Date} ORDER BY captured_at LIMIT 1
        """, parameters={"day": business_date}).result_rows
    if prior:
        return prior[0][0]
    batch = uuid4()
    with source.transaction(), source.cursor(row_factory=dict_row) as cursor:
        cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        cursor.execute("SELECT CURRENT_TIMESTAMP AS sampled_at")
        # PostgreSQL's transaction timestamp is the actual beginning of this sample.
        captured_at = cursor.fetchone()["sampled_at"] if now is None else sampled_at
        records = {}
        for projection in PROJECTIONS:
            cursor.execute(f"SELECT * FROM warehouse_source_{projection}")
            records[projection] = cursor.fetchall()
    normalized = normalize_snapshot(records, batch, captured_at, source_zone)
    for offset in range(0, len(normalized), 1000):
        clickhouse.insert("ods_snapshots", normalized[offset:offset + 1000], column_names=list(SNAPSHOT_COLUMNS))
    # A completion marker is written only after every projection was persisted.
    clickhouse.insert("ods_snapshot_batches", [[batch, business_date, captured_at, len(normalized)]],
                      column_names=["batch_id", "business_date", "captured_at", "record_count"])
    return batch
