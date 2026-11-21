package com.betterclouddrive.dal.repository;

import com.betterclouddrive.dal.repository.InspectionRecords.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InspectionFindingRepository {
    private final JdbcTemplate jdbc;
    private static OffsetDateTime time(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

    public Optional<StoredFinding> lockIdentity(String identity) {
        return jdbc.query("SELECT * FROM storage_inspection_findings WHERE identity=? FOR UPDATE", (r, i) ->
                new StoredFinding(new FindingDetail(StorageInspectionRepository.FINDING.mapRow(r, i), r.getString("object_key")),
                        r.getString("signature"), StorageInspectionRepository.instant(r, "last_counted_at")), identity)
                .stream().findFirst();
    }

    public void insert(UUID id, UUID run, String identity, String fingerprint, Observation evidence, Instant now) {
        jdbc.update("""
                INSERT INTO storage_inspection_findings (id, identity, type, reference_kind, reference_id, object_key,
                    object_fingerprint, scope, owner_id, severity, status, signature, first_seen_at, stable_since,
                    last_observed_at, last_counted_at, last_seen_run, expected_bytes, observed_bytes, object_modified_at, metadata_reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUSPECTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, identity, evidence.type(), evidence.referenceKind(), evidence.referenceId(), evidence.key(), fingerprint,
                evidence.scope(), evidence.owner(), evidence.severity(), evidence.signature(), time(now), time(now), time(now),
                time(now), run, evidence.expected(), evidence.observed(), time(evidence.modifiedAt()), evidence.metadataReason());
    }

    public void update(UUID id, UUID run, Observation evidence, String status, String review, int observations,
                       Instant stableSince, Instant lastCounted, Instant now) {
        jdbc.update("""
                UPDATE storage_inspection_findings SET signature=?, severity=?, status=?, review_status=?, observations=?,
                    stable_since=?, last_counted_at=?, last_observed_at=?, last_seen_run=?, expected_bytes=?, observed_bytes=?,
                    object_modified_at=?, resolved_at=NULL WHERE id=?
                """, evidence.signature(), evidence.severity(), status, review, observations, time(stableSince), time(lastCounted),
                time(now), run, evidence.expected(), evidence.observed(), time(evidence.modifiedAt()), id);
    }

    public void resolve(UUID id, UUID run, Instant now) {
        jdbc.update("""
                UPDATE storage_inspection_findings SET status='RESOLVED', resolved_at=?, last_observed_at=?, last_seen_run=?
                WHERE id=? AND status <> 'RESOLVED'
                """, time(now), time(now), run, id);
    }

    public void resolveAfterRepair(UUID finding) {
        jdbc.update("UPDATE storage_inspection_findings SET status='RESOLVED', resolved_at=now() WHERE id=?", finding);
    }

    public List<FindingDetail> openAfter(UUID after, int limit) {
        return jdbc.query("""
                SELECT * FROM storage_inspection_findings WHERE status <> 'RESOLVED' AND id>?
                ORDER BY id LIMIT ?
                """, (r, i) -> new FindingDetail(StorageInspectionRepository.FINDING.mapRow(r, i), r.getString("object_key")),
                after == null ? new UUID(0, 0) : after, limit);
    }

    private record Selection(String where, Object[] parameters) {}
    private Selection selection(FindingFilter filter) {
        var sql = new StringBuilder(" WHERE 1=1");
        var parameters = new ArrayList<Object>();
        String[] columns = {"type", "status", "review_status", "scope", "owner_id"};
        Object[] values = {filter.type(), filter.status(), filter.reviewStatus(), filter.scope(), filter.ownerId()};
        for (int i = 0; i < columns.length; i++) {
            if (values[i] != null) { sql.append(" AND ").append(columns[i]).append("=?"); parameters.add(values[i]); }
        }
        return new Selection(sql.toString(), parameters.toArray());
    }

    public List<Finding> list(FindingFilter filter, int offset, int limit) {
        var selected = selection(filter);
        var arguments = new ArrayList<>(List.of(selected.parameters()));
        arguments.add(limit); arguments.add(offset);
        return jdbc.query("SELECT * FROM storage_inspection_findings" + selected.where()
                + " ORDER BY last_observed_at DESC, id DESC LIMIT ? OFFSET ?", StorageInspectionRepository.FINDING, arguments.toArray());
    }

    public long count(FindingFilter filter) {
        var selected = selection(filter);
        return jdbc.queryForObject("SELECT count(*) FROM storage_inspection_findings" + selected.where(), Long.class, selected.parameters());
    }

    public List<Map<String, Object>> counts() {
        return jdbc.queryForList("SELECT type, status, severity, review_status, count(*) AS count FROM storage_inspection_findings GROUP BY type, status, severity, review_status");
    }

    private static final RowMapper<Review> REVIEW = (r, i) -> new Review(r.getObject("id", UUID.class), r.getObject("finding_id", UUID.class),
            r.getLong("actor_id"), r.getString("action"), r.getString("reason"), StorageInspectionRepository.instant(r, "created_at"));

    public Review review(UUID id, UUID finding, long actor, String action, String reason) {
        return jdbc.query("""
                INSERT INTO storage_inspection_reviews (id, finding_id, actor_id, action, reason) VALUES (?, ?, ?, ?, ?) RETURNING *
                """, REVIEW, id, finding, actor, action, reason).getFirst();
    }

    public void reviewStatus(UUID finding, String status) {
        jdbc.update("UPDATE storage_inspection_findings SET review_status=? WHERE id=?", status, finding);
    }

    public List<Review> reviews(UUID finding) {
        return jdbc.query("SELECT * FROM storage_inspection_reviews WHERE finding_id=? ORDER BY created_at DESC, id DESC LIMIT 200", REVIEW, finding);
    }

    private static final RowMapper<Preview> PREVIEW = (r, i) -> new Preview(r.getObject("id", UUID.class), r.getObject("finding_id", UUID.class),
            r.getLong("actor_id"), r.getString("scope"), r.getLong("owner_id"), r.getLong("recorded_bytes"), r.getLong("expected_bytes"),
            r.getLong("quota_revision"), StorageInspectionRepository.instant(r, "created_at"), StorageInspectionRepository.instant(r, "expires_at"));

    public Preview preview(UUID id, UUID finding, long actor, String scope, long owner, long recorded, long expected, long revision) {
        return jdbc.query("""
                INSERT INTO storage_quota_repair_previews (id, finding_id, actor_id, scope, owner_id, recorded_bytes, expected_bytes, quota_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, PREVIEW, id, finding, actor, scope, owner, recorded, expected, revision).getFirst();
    }

    public Optional<Preview> preview(UUID id) {
        return jdbc.query("SELECT * FROM storage_quota_repair_previews WHERE id=?", PREVIEW, id).stream().findFirst();
    }

    private static final RowMapper<Repair> REPAIR = (r, i) -> new Repair(r.getObject("id", UUID.class), r.getObject("preview_id", UUID.class),
            r.getObject("finding_id", UUID.class), r.getLong("actor_id"), r.getObject("idempotency_key", UUID.class), r.getString("scope"),
            r.getLong("owner_id"), r.getLong("used_before"), r.getLong("used_after"), r.getLong("quota_revision_before"),
            r.getString("reason"), r.getInt("rule_version"), StorageInspectionRepository.instant(r, "created_at"));

    public Repair repair(UUID id, Preview preview, UUID key, String reason) {
        return jdbc.query("""
                INSERT INTO storage_quota_repairs (id, preview_id, finding_id, actor_id, idempotency_key, scope,
                    owner_id, used_before, used_after, quota_revision_before, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, REPAIR, id, preview.id(), preview.findingId(), preview.actorId(), key, preview.scope(), preview.ownerId(),
                preview.recordedBytes(), preview.expectedBytes(), preview.quotaRevision(), reason).getFirst();
    }

    public Optional<Repair> repairByKey(UUID key) {
        return jdbc.query("SELECT * FROM storage_quota_repairs WHERE idempotency_key=?", REPAIR, key).stream().findFirst();
    }

    public List<Repair> repairs(UUID finding) {
        return jdbc.query("SELECT * FROM storage_quota_repairs WHERE finding_id=? ORDER BY created_at DESC, id DESC LIMIT 200", REPAIR, finding);
    }
}
