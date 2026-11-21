package com.betterclouddrive.dal.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bounded deletion of derived inspection data only; never touches source files or objects. */
@Repository
@RequiredArgsConstructor
public class InspectionRetentionRepository {
    private final JdbcTemplate jdbc;

    public int prune(int batch) {
        if (batch < 1 || batch > 10000) throw new IllegalArgumentException("invalid_inspection_retention_batch");
        int removed = 0;
        for (String table : new String[]{"storage_inspection_objects", "storage_inspection_references", "storage_inspection_scopes", "storage_inspection_tasks", "storage_inspection_metadata_issues"}) {
            removed += jdbc.update("DELETE FROM " + table + " WHERE ctid IN (SELECT s.ctid FROM " + table + " s"
                    + " JOIN storage_inspection_runs r ON r.id=s.run_id WHERE r.created_at < now()-INTERVAL '7 days'"
                    + " AND r.status NOT IN ('QUEUED','RUNNING') LIMIT ?)", batch);
        }
        removed += jdbc.update("DELETE FROM storage_quota_repairs WHERE id IN (SELECT id FROM storage_quota_repairs WHERE created_at < now()-INTERVAL '365 days' LIMIT ?)", batch);
        removed += jdbc.update("DELETE FROM storage_inspection_reviews WHERE id IN (SELECT id FROM storage_inspection_reviews WHERE created_at < now()-INTERVAL '365 days' LIMIT ?)", batch);
        removed += jdbc.update("""
                DELETE FROM storage_quota_repair_previews WHERE id IN (
                    SELECT p.id FROM storage_quota_repair_previews p WHERE p.expires_at < now()-INTERVAL '7 days'
                    AND NOT EXISTS (SELECT 1 FROM storage_quota_repairs r WHERE r.preview_id=p.id) LIMIT ?)
                """, batch);
        removed += jdbc.update("""
                DELETE FROM storage_inspection_findings WHERE id IN (
                    SELECT f.id FROM storage_inspection_findings f WHERE f.status='RESOLVED' AND f.resolved_at < now()-INTERVAL '90 days'
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_reviews r WHERE r.finding_id=f.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_quota_repairs r WHERE r.finding_id=f.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_quota_repair_previews p WHERE p.finding_id=f.id) LIMIT ?)
                """, batch);
        removed += jdbc.update("""
                DELETE FROM storage_inspection_runs WHERE id IN (
                    SELECT r.id FROM storage_inspection_runs r WHERE r.created_at < now()-INTERVAL '90 days'
                    AND r.status NOT IN ('QUEUED','RUNNING')
                    AND r.id IS DISTINCT FROM (SELECT id FROM storage_inspection_runs WHERE kind='FULL' AND status='SUCCEEDED' ORDER BY finished_at DESC LIMIT 1)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_findings f WHERE f.last_seen_run=r.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_objects s WHERE s.run_id=r.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_references s WHERE s.run_id=r.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_scopes s WHERE s.run_id=r.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_tasks s WHERE s.run_id=r.id)
                    AND NOT EXISTS (SELECT 1 FROM storage_inspection_metadata_issues s WHERE s.run_id=r.id) LIMIT ?)
                """, batch);
        return removed;
    }
}
