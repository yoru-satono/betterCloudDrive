package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.repository.InspectionRecords.*;
import com.betterclouddrive.common.dto.StorageInventory.ObjectInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StorageInspectionRepository {
    private final JdbcTemplate jdbc;

    static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static Long number(ResultSet row, String column) throws SQLException { return row.getObject(column, Long.class); }
    private static final RowMapper<Run> RUN = (r, i) -> new Run(r.getObject("id", UUID.class), r.getString("kind"),
            r.getObject("finding_id", UUID.class), r.getString("status"), r.getString("phase"), r.getInt("rule_version"),
            number(r, "requested_by"), instant(r, "created_at"), instant(r, "started_at"), instant(r, "finished_at"),
            instant(r, "metadata_at"), instant(r, "scan_started_at"), instant(r, "scan_finished_at"),
            r.getBoolean("metadata_complete"), r.getBoolean("inventory_complete"), r.getBoolean("cancel_requested"),
            r.getLong("objects_scanned"), r.getBigDecimal("object_bytes"), r.getLong("references_scanned"),
            r.getLong("candidates_checked"), r.getString("error_key"));
    private static final RowMapper<Reference> REFERENCE = (r, i) -> new Reference(r.getLong("id"), r.getString("kind"),
            r.getLong("reference_id"), number(r, "file_id"), r.getString("object_key"), number(r, "expected_bytes"),
            r.getString("scope"), r.getLong("owner_id"), number(r, "listed_bytes"));
    private static final RowMapper<ScopeFact> SCOPE = (r, i) -> new ScopeFact(r.getLong("id"), r.getString("scope"),
            r.getLong("owner_id"), r.getLong("recorded_bytes"), r.getLong("quota_revision"), number(r, "expected_bytes"),
            r.getBoolean("metadata_valid"), r.getLong("invalid_count"));
    static final RowMapper<Finding> FINDING = (r, i) -> new Finding(r.getObject("id", UUID.class), r.getString("type"),
            r.getString("reference_kind"), r.getString("reference_id"), r.getString("object_fingerprint"), r.getString("scope"),
            number(r, "owner_id"), r.getString("severity"), r.getString("status"), r.getString("review_status"),
            instant(r, "first_seen_at"), instant(r, "stable_since"), instant(r, "last_observed_at"), r.getInt("observations"),
            number(r, "expected_bytes"), number(r, "observed_bytes"), instant(r, "object_modified_at"),
            r.getObject("last_seen_run", UUID.class), instant(r, "resolved_at"), r.getString("metadata_reason"));

    public Run enqueue(UUID id, String kind, UUID finding, Long actor) {
        return jdbc.query("""
                INSERT INTO storage_inspection_runs (id, kind, finding_id, status, requested_by, phase)
                VALUES (?, ?, ?, 'QUEUED', ?, ?) RETURNING *
                """, RUN, id, kind, finding, actor, "RECHECK".equals(kind) ? "RECHECK" : "METADATA").getFirst();
    }

    public Optional<Claim> claim(UUID token, long leaseSeconds) {
        return jdbc.query("""
                WITH next AS (
                    SELECT id FROM storage_inspection_runs WHERE status='QUEUED'
                        OR (status='RUNNING' AND lease_until < clock_timestamp())
                    ORDER BY created_at, id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE storage_inspection_runs r SET status='RUNNING', started_at=COALESCE(started_at, now()),
                    claim_token=?, lease_until=clock_timestamp() + (? * INTERVAL '1 second')
                FROM next WHERE r.id=next.id RETURNING r.*
                """, (r, i) -> new Claim(RUN.mapRow(r, i), r.getObject("claim_token", UUID.class), r.getString("cursor")),
                token, leaseSeconds).stream().findFirst();
    }

    public Optional<Run> run(UUID id) {
        return jdbc.query("SELECT * FROM storage_inspection_runs WHERE id=?", RUN, id).stream().findFirst();
    }

    public List<Run> runs(int offset, int limit) {
        return jdbc.query("SELECT * FROM storage_inspection_runs ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                RUN, limit, offset);
    }

    public long runCount() { return jdbc.queryForObject("SELECT count(*) FROM storage_inspection_runs", Long.class); }

    public Optional<Run> latestComplete() {
        return jdbc.query("SELECT * FROM storage_inspection_runs WHERE kind='FULL' AND status='SUCCEEDED' ORDER BY finished_at DESC LIMIT 1",
                RUN).stream().findFirst();
    }

    public Optional<Run> latestFull() {
        return jdbc.query("SELECT * FROM storage_inspection_runs WHERE kind='FULL' ORDER BY created_at DESC, id DESC LIMIT 1", RUN)
                .stream().findFirst();
    }

    public void requestCancel(UUID id) {
        jdbc.update("UPDATE storage_inspection_runs SET cancel_requested=true WHERE id=? AND status IN ('QUEUED','RUNNING')", id);
    }

    public Claim guard(Claim claim) {
        return jdbc.query("""
                SELECT * FROM storage_inspection_runs WHERE id=? AND claim_token=? AND status='RUNNING'
                    AND lease_until > clock_timestamp() FOR UPDATE
                """, (r, i) -> new Claim(RUN.mapRow(r, i), claim.token(), r.getString("cursor")), claim.run().id(), claim.token())
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("inspection_lease_lost"));
    }

    public Claim progress(Claim claim, String phase, String cursor, long objects, BigDecimal bytes,
                          long references, long checked, boolean metadata, boolean inventory, long leaseSeconds) {
        var rows = jdbc.query("""
                UPDATE storage_inspection_runs SET phase=?, cursor=?, objects_scanned=objects_scanned+?,
                    object_bytes=object_bytes+?, references_scanned=references_scanned+?, candidates_checked=candidates_checked+?,
                    metadata_complete=metadata_complete OR ?, inventory_complete=inventory_complete OR ?,
                    metadata_at=CASE WHEN ? THEN COALESCE(metadata_at, now()) ELSE metadata_at END,
                    scan_started_at=CASE WHEN ?='INVENTORY' THEN COALESCE(scan_started_at,now()) ELSE scan_started_at END,
                    scan_finished_at=CASE WHEN ? THEN COALESCE(scan_finished_at, now()) ELSE scan_finished_at END,
                    lease_until=clock_timestamp() + (? * INTERVAL '1 second')
                WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>clock_timestamp() RETURNING *
                """, (r, i) -> new Claim(RUN.mapRow(r, i), claim.token(), r.getString("cursor")), phase, cursor,
                objects, bytes, references, checked, metadata, inventory, metadata, phase, inventory, leaseSeconds,
                claim.run().id(), claim.token());
        if (rows.isEmpty()) throw new IllegalStateException("inspection_lease_lost");
        return rows.getFirst();
    }

    public void finish(Claim claim, String status, String errorKey) {
        if (jdbc.update("""
                UPDATE storage_inspection_runs SET status=?, error_key=?, finished_at=now(), lease_until=NULL
                WHERE id=? AND claim_token=? AND status='RUNNING' AND lease_until>clock_timestamp()
                """, status, errorKey, claim.run().id(), claim.token()) != 1) {
            throw new IllegalStateException("inspection_lease_lost");
        }
    }

    public long snapshot(UUID run, String sourceZone) {
        jdbc.execute("SET LOCAL statement_timeout = '120s'");
        jdbc.update("DELETE FROM storage_inspection_references WHERE run_id=?", run);
        jdbc.update("DELETE FROM storage_inspection_scopes WHERE run_id=?", run);
        jdbc.update("DELETE FROM storage_inspection_tasks WHERE run_id=?", run);
        jdbc.update("DELETE FROM storage_inspection_metadata_issues WHERE run_id=?", run);
        long references = jdbc.update("""
                INSERT INTO storage_inspection_references (run_id, kind, reference_id, file_id, object_key, expected_bytes, scope, owner_id)
                SELECT ?, kind, reference_id, file_id, object_key, expected_bytes, scope, owner_id FROM inspection_live_references
                """, run);
        jdbc.update("""
                INSERT INTO storage_inspection_scopes (run_id, scope, owner_id, recorded_bytes, quota_revision,
                    expected_bytes, metadata_valid, invalid_count)
                SELECT ?, owner.scope, owner.id, owner.storage_used, owner.quota_revision,
                    fact.expected_bytes, fact.metadata_valid, fact.invalid_count
                FROM (
                    SELECT 'PERSONAL'::TEXT AS scope, id, storage_used, quota_revision FROM users WHERE deleted_at IS NULL
                    UNION ALL SELECT 'TEAM', id, storage_used, quota_revision FROM teams WHERE deleted_at IS NULL
                ) owner CROSS JOIN LATERAL inspection_quota_facts(owner.scope, owner.id) fact
                """, run);
        jdbc.update("""
                INSERT INTO storage_inspection_tasks (run_id, kind, source_id, scope, owner_id, status, created_at, updated_at, started_at)
                SELECT ?, 'UPLOAD', id, CASE WHEN team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END,
                    COALESCE(team_id, user_id), status::TEXT, created_at AT TIME ZONE ?, updated_at AT TIME ZONE ?, NULL
                FROM upload_sessions WHERE status=1
                """, run, sourceZone, sourceZone);
        jdbc.update("""
                INSERT INTO storage_inspection_tasks (run_id, kind, source_id, scope, owner_id, status, created_at, updated_at, started_at)
                SELECT ?, 'TEAM_TASK', id::TEXT, CASE WHEN team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END,
                    COALESCE(team_id, actor_user_id, 0), status, created_at AT TIME ZONE ?, updated_at AT TIME ZONE ?,
                    started_at AT TIME ZONE ? FROM team_tasks WHERE status IN ('PENDING','RUNNING')
                """, run, sourceZone, sourceZone, sourceZone);
        jdbc.update("""
                INSERT INTO storage_inspection_metadata_issues (run_id, kind, reference_id, scope, owner_id, reason, observed_bytes, signature)
                SELECT ?, kind, reference_id, scope, owner_id, reason, observed_bytes, signature FROM inspection_live_metadata_issues
                """, run);
        return references;
    }

    private static final RowMapper<MetadataIssue> METADATA_ISSUE = (r, i) -> new MetadataIssue(r.getLong("id"), r.getString("kind"),
            r.getLong("reference_id"), r.getString("scope"), r.getLong("owner_id"), r.getString("reason"),
            number(r, "observed_bytes"), r.getString("signature"));

    public List<MetadataIssue> metadataIssues(UUID run, long after, int limit) {
        return jdbc.query("SELECT * FROM storage_inspection_metadata_issues WHERE run_id=? AND id>? ORDER BY id LIMIT ?",
                METADATA_ISSUE, run, after, limit);
    }

    public Optional<MetadataIssue> liveMetadataIssue(String kind, long id, String reason) {
        return jdbc.query("SELECT 0 AS id, m.* FROM inspection_live_metadata_issues m WHERE kind=? AND reference_id=? AND reason=?",
                METADATA_ISSUE, kind, id, reason).stream().findFirst();
    }

    private static final RowMapper<TaskRef> TASK = (r, i) -> new TaskRef(r.getLong("id"), r.getString("kind"),
            r.getString("source_id"), r.getString("scope"), r.getLong("owner_id"), r.getString("status"),
            instant(r, "created_at"), instant(r, "updated_at"), instant(r, "started_at"));

    public List<TaskRef> tasks(UUID run, long after, int limit) {
        return jdbc.query("SELECT * FROM storage_inspection_tasks WHERE run_id=? AND id>? ORDER BY id LIMIT ?", TASK, run, after, limit);
    }

    public Optional<TaskRef> liveTask(String kind, String id, String zone) {
        if ("UPLOAD".equals(kind)) {
            return jdbc.query("""
                    SELECT 0 AS id, 'UPLOAD' AS kind, id AS source_id,
                        CASE WHEN team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END AS scope,
                        COALESCE(team_id,user_id) AS owner_id, status::TEXT AS status,
                        created_at AT TIME ZONE ? AS created_at, updated_at AT TIME ZONE ? AS updated_at,
                        NULL::TIMESTAMPTZ AS started_at FROM upload_sessions WHERE id=?
                    """, TASK, zone, zone, id).stream().findFirst();
        }
        return jdbc.query("""
                SELECT 0 AS id, 'TEAM_TASK' AS kind, id::TEXT AS source_id,
                    CASE WHEN team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END AS scope,
                    COALESCE(team_id,actor_user_id,0) AS owner_id, status,
                    created_at AT TIME ZONE ? AS created_at, updated_at AT TIME ZONE ? AS updated_at,
                    started_at AT TIME ZONE ? AS started_at FROM team_tasks WHERE id=?
                """, TASK, zone, zone, zone, Long.parseLong(id)).stream().findFirst();
    }

    public boolean activeUpload(String key, String zone, long expireSeconds) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM upload_sessions u WHERE u.status=1
                        AND u.created_at AT TIME ZONE ? > now() - (? * INTERVAL '1 second')
                        AND starts_with(?, CASE WHEN u.team_id IS NULL THEN 'uploads/' || u.user_id
                            ELSE 'uploads/teams/' || u.team_id END || '/' || u.id || '/chunks.part.')
                )
                """, Boolean.class, zone, expireSeconds, key));
    }

    public record InventoryDelta(long objects, BigDecimal bytes) {}

    public InventoryDelta objects(UUID run, List<ObjectInfo> objects) {
        if (objects.isEmpty()) return new InventoryDelta(0, BigDecimal.ZERO);
        var existing = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(
                "SELECT object_key, size_bytes FROM storage_inspection_objects WHERE run_id=:run AND object_key IN (:keys)",
                java.util.Map.of("run", run, "keys", objects.stream().map(ObjectInfo::key).toList()), result -> {
                    var sizes = new java.util.HashMap<String, Long>();
                    while (result.next()) sizes.put(result.getString(1), result.getLong(2));
                    return sizes;
                });
        long added = 0;
        BigDecimal bytes = BigDecimal.ZERO;
        for (ObjectInfo object : objects) {
            if (!existing.containsKey(object.key())) added++;
            bytes = bytes.add(BigDecimal.valueOf(object.size()).subtract(BigDecimal.valueOf(existing.getOrDefault(object.key(), 0L))));
        }
        jdbc.batchUpdate("""
                INSERT INTO storage_inspection_objects (run_id, object_key, size_bytes, etag, modified_at)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (run_id, object_key) DO UPDATE
                    SET size_bytes=EXCLUDED.size_bytes, etag=EXCLUDED.etag, modified_at=EXCLUDED.modified_at
                """, objects, 1000, (statement, object) -> {
            statement.setObject(1, run); statement.setString(2, object.key()); statement.setLong(3, object.size());
            statement.setString(4, object.etag() == null ? "" : object.etag());
            statement.setObject(5, object.modifiedAt().atOffset(java.time.ZoneOffset.UTC));
        });
        return new InventoryDelta(added, bytes);
    }

    public void inventoryGap(Claim claim) {
        guard(claim);
        jdbc.update("UPDATE storage_inspection_runs SET inventory_complete=false, error_key='inspection_inventory_gap' WHERE id=? AND claim_token=?",
                claim.run().id(), claim.token());
    }

    public List<Reference> references(UUID run, long after, int limit) {
        return jdbc.query("""
                SELECT r.*, o.size_bytes AS listed_bytes FROM storage_inspection_references r
                LEFT JOIN storage_inspection_objects o ON o.run_id=r.run_id AND o.object_key=r.object_key
                WHERE r.run_id=? AND r.id>? ORDER BY r.id LIMIT ?
                """, REFERENCE, run, after, limit);
    }

    public Optional<Reference> liveReference(String kind, long id) {
        return jdbc.query("SELECT 0 AS id, r.*, NULL::BIGINT AS listed_bytes FROM inspection_live_references r WHERE kind=? AND reference_id=?",
                REFERENCE, kind, id).stream().findFirst();
    }

    public boolean referenced(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM inspection_live_references WHERE object_key=?)",
                Boolean.class, key));
    }

    public List<ObjectInfo> unreferencedObjects(UUID run, String after, int limit) {
        return jdbc.query("""
                SELECT o.* FROM storage_inspection_objects o WHERE o.run_id=? AND o.object_key>?
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_references r WHERE r.run_id=o.run_id AND r.object_key=o.object_key)
                ORDER BY o.object_key LIMIT ?
                """, (r, i) -> new ObjectInfo(r.getString("object_key"), r.getLong("size_bytes"), r.getString("etag"),
                instant(r, "modified_at")), run, after == null ? "" : after, limit);
    }

    public List<ScopeFact> scopes(UUID run, long after, int limit) {
        return jdbc.query("SELECT * FROM storage_inspection_scopes WHERE run_id=? AND id>? ORDER BY id LIMIT ?",
                SCOPE, run, after, limit);
    }

    public QuotaFact quotaFact(String scope, long owner) {
        return jdbc.query("SELECT * FROM inspection_quota_facts(?, ?)",
                (r, i) -> new QuotaFact(number(r, "expected_bytes"), r.getBoolean("metadata_valid"), r.getLong("invalid_count"), r.getString("metadata_signature")),
                scope, owner).getFirst();
    }

    public Optional<FindingDetail> finding(UUID id) {
        return jdbc.query("SELECT * FROM storage_inspection_findings WHERE id=?", (r, i) ->
                new FindingDetail(FINDING.mapRow(r, i), r.getString("object_key")), id).stream().findFirst();
    }
}
