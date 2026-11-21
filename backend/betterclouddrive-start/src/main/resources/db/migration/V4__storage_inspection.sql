-- The same source calculation is used by inspection and locked repair.
-- Version.user_id identifies an editor, not necessarily the quota owner.
CREATE FUNCTION inspection_quota_facts(p_scope TEXT, p_owner BIGINT)
RETURNS TABLE (expected_bytes BIGINT, metadata_valid BOOLEAN, invalid_count BIGINT, metadata_signature TEXT)
LANGUAGE SQL STABLE AS $$
    WITH RECURSIVE owned AS MATERIALIZED (
        SELECT id, parent_id, file_type, file_size, is_deleted, storage_path FROM files
        WHERE (p_scope = 'PERSONAL' AND team_id IS NULL AND user_id = p_owner)
           OR (p_scope = 'TEAM' AND team_id = p_owner)
    ), tree AS (
        SELECT id, file_type, file_size, NOT is_deleted AS active FROM owned WHERE parent_id IS NULL
        UNION ALL
        SELECT child.id, child.file_type, child.file_size, parent.active AND NOT child.is_deleted
        FROM owned child JOIN tree parent ON child.parent_id = parent.id WHERE parent.file_type = 'folder'
    ), versions AS MATERIALIZED (
        SELECT v.id, v.file_id, v.file_size, v.storage_path, f.file_type AS parent_type FROM file_versions v JOIN owned f ON f.id = v.file_id
    ), totals AS (
        SELECT
            (SELECT count(*) FROM owned) - (SELECT count(*) FROM tree)
                + (SELECT count(*) FROM owned WHERE file_type NOT IN ('file','folder') OR file_type IS NULL
                    OR file_size IS NULL OR file_size < 0 OR is_deleted IS NULL
                    OR (file_type = 'file' AND (storage_path IS NULL OR btrim(storage_path) = '')))
                + (SELECT count(*) FROM versions WHERE file_size IS NULL OR file_size < 0 OR parent_type <> 'file'
                    OR storage_path IS NULL OR btrim(storage_path) = '') AS invalid,
            COALESCE((SELECT sum(file_size) FROM tree WHERE active AND file_type = 'file'), 0)
                + COALESCE((SELECT sum(v.file_size) FROM versions v JOIN tree f ON f.id=v.file_id
                    WHERE f.active AND f.file_type = 'file'), 0) AS bytes
    )
    SELECT CASE WHEN invalid = 0 AND bytes BETWEEN 0 AND 9223372036854775807 THEN bytes::BIGINT ELSE NULL END,
           invalid = 0 AND bytes BETWEEN 0 AND 9223372036854775807,
           invalid + CASE WHEN bytes BETWEEN 0 AND 9223372036854775807 THEN 0 ELSE 1 END,
           md5(COALESCE((SELECT bit_xor(hashtextextended(concat_ws(':', id, parent_id, file_type, file_size,
                    is_deleted, storage_path), 0)) FROM owned), 0)::TEXT || ':' ||
               COALESCE((SELECT bit_xor(hashtextextended(concat_ws(':', id, file_id, file_size, storage_path), 0)) FROM versions), 0)::TEXT)
    FROM totals;
$$;

-- Restricted inspection data; never grant these views to the warehouse exporter.
CREATE VIEW inspection_live_references AS
    SELECT 'FILE'::TEXT AS kind, f.id AS reference_id, f.id AS file_id, f.storage_path AS object_key,
           f.file_size AS expected_bytes, CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END AS scope,
           COALESCE(f.team_id, f.user_id) AS owner_id
    FROM files f WHERE f.file_type='file' AND NULLIF(btrim(f.storage_path), '') IS NOT NULL
    UNION ALL
    SELECT 'VERSION', v.id, f.id, v.storage_path, v.file_size,
           CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END, COALESCE(f.team_id, f.user_id)
    FROM file_versions v JOIN files f ON f.id=v.file_id WHERE NULLIF(btrim(v.storage_path), '') IS NOT NULL
    UNION ALL
    SELECT 'THUMBNAIL', f.id, f.id, f.thumbnail_path, NULL::BIGINT,
           CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END, COALESCE(f.team_id, f.user_id)
    FROM files f WHERE NULLIF(btrim(f.thumbnail_path), '') IS NOT NULL
    UNION ALL
    SELECT 'ZIP_CACHE', z.id, f.id, z.object_key, z.file_size,
           CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END, COALESCE(f.team_id, f.user_id)
    FROM folder_zip_download_cache z JOIN files f ON f.id=z.folder_id
    UNION ALL
    SELECT 'ZIP_TASK', t.id, t.target_id, t.result_path, t.result_size_bytes, 'TEAM', t.team_id
    FROM team_tasks t WHERE t.task_type='FOLDER_ZIP' AND t.status='SUCCEEDED' AND t.team_id IS NOT NULL
        AND NULLIF(btrim(t.result_path), '') IS NOT NULL;

CREATE VIEW inspection_live_metadata_issues AS
    WITH RECURSIVE reachable AS (
        SELECT id, user_id, team_id, file_type FROM files WHERE parent_id IS NULL
        UNION ALL
        SELECT f.id, f.user_id, f.team_id, f.file_type FROM files f JOIN reachable p ON p.id=f.parent_id
            AND p.file_type='folder' AND p.team_id IS NOT DISTINCT FROM f.team_id
            AND (f.team_id IS NOT NULL OR p.user_id=f.user_id)
    )
    SELECT 'FILE'::TEXT AS kind, f.id AS reference_id,
        CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END AS scope,
        COALESCE(f.team_id,f.user_id) AS owner_id, issue.reason,
        CASE WHEN issue.reason='INVALID_SIZE' THEN f.file_size ELSE NULL END AS observed_bytes,
        md5(concat_ws(':', f.id, f.parent_id, f.file_type, f.file_size, f.is_deleted, f.storage_path, issue.reason)) AS signature
    FROM files f LEFT JOIN files parent ON parent.id=f.parent_id
    CROSS JOIN LATERAL (VALUES
        ('INVALID_ANCESTRY', NOT EXISTS (SELECT 1 FROM reachable r WHERE r.id=f.id)),
        ('MISSING_PARENT', f.parent_id IS NOT NULL AND parent.id IS NULL),
        ('CROSS_SPACE_PARENT', parent.id IS NOT NULL AND (parent.team_id IS DISTINCT FROM f.team_id
            OR (f.team_id IS NULL AND parent.user_id IS DISTINCT FROM f.user_id))),
        ('NON_FOLDER_PARENT', parent.id IS NOT NULL AND parent.file_type IS DISTINCT FROM 'folder'),
        ('INVALID_KIND', f.file_type IS NULL OR f.file_type NOT IN ('file','folder')),
        ('INVALID_SIZE', f.file_size IS NULL OR f.file_size<0),
        ('INVALID_STATE', f.is_deleted IS NULL),
        ('MISSING_OBJECT_KEY', f.file_type='file' AND NULLIF(btrim(f.storage_path),'') IS NULL)
    ) issue(reason, failed) WHERE issue.failed
    UNION ALL
    SELECT 'VERSION', v.id, CASE WHEN f.team_id IS NULL THEN 'PERSONAL' ELSE 'TEAM' END,
        COALESCE(f.team_id,f.user_id), issue.reason,
        CASE WHEN issue.reason='INVALID_SIZE' THEN v.file_size ELSE NULL END,
        md5(concat_ws(':', v.id, v.file_id, v.file_size, v.storage_path, issue.reason))
    FROM file_versions v JOIN files f ON f.id=v.file_id
    CROSS JOIN LATERAL (VALUES
        ('INVALID_SIZE', v.file_size IS NULL OR v.file_size<0),
        ('MISSING_OBJECT_KEY', NULLIF(btrim(v.storage_path),'') IS NULL),
        ('VERSION_PARENT_NOT_FILE', f.file_type IS DISTINCT FROM 'file')
    ) issue(reason, failed) WHERE issue.failed;

CREATE TABLE storage_inspection_runs (
    id UUID PRIMARY KEY,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('FULL','RECHECK')),
    finding_id UUID,
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','PARTIAL','FAILED','CANCELED')),
    phase VARCHAR(24) NOT NULL DEFAULT 'METADATA',
    rule_version INTEGER NOT NULL DEFAULT 1,
    requested_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    metadata_at TIMESTAMPTZ,
    scan_started_at TIMESTAMPTZ,
    scan_finished_at TIMESTAMPTZ,
    metadata_complete BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_complete BOOLEAN NOT NULL DEFAULT FALSE,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    cursor TEXT,
        objects_scanned BIGINT NOT NULL DEFAULT 0,
    object_bytes NUMERIC(30,0) NOT NULL DEFAULT 0,
    references_scanned BIGINT NOT NULL DEFAULT 0,
    candidates_checked BIGINT NOT NULL DEFAULT 0,
    error_key VARCHAR(80)
);
CREATE UNIQUE INDEX storage_inspection_one_active ON storage_inspection_runs ((true))
    WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX storage_inspection_runs_recent ON storage_inspection_runs (created_at DESC, id);

CREATE TABLE storage_inspection_references (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES storage_inspection_runs(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    reference_id BIGINT NOT NULL,
    file_id BIGINT,
    object_key TEXT NOT NULL,
    expected_bytes BIGINT,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    UNIQUE (run_id, kind, reference_id)
);
CREATE INDEX storage_inspection_references_keys ON storage_inspection_references (run_id, object_key);

CREATE TABLE storage_inspection_scopes (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES storage_inspection_runs(id) ON DELETE CASCADE,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    recorded_bytes BIGINT NOT NULL,
    quota_revision BIGINT NOT NULL,
    expected_bytes BIGINT,
    metadata_valid BOOLEAN NOT NULL,
    invalid_count BIGINT NOT NULL,
    UNIQUE (run_id, scope, owner_id)
);

CREATE TABLE storage_inspection_objects (
    run_id UUID NOT NULL REFERENCES storage_inspection_runs(id) ON DELETE CASCADE,
    object_key TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    etag TEXT NOT NULL,
    modified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, object_key)
);

CREATE TABLE storage_inspection_tasks (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES storage_inspection_runs(id) ON DELETE CASCADE,
    kind VARCHAR(12) NOT NULL,
    source_id VARCHAR(80) NOT NULL,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    status VARCHAR(12) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    UNIQUE (run_id, kind, source_id)
);
CREATE INDEX storage_inspection_tasks_page ON storage_inspection_tasks (run_id, id);

CREATE TABLE storage_inspection_metadata_issues (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES storage_inspection_runs(id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    reference_id BIGINT NOT NULL,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    reason VARCHAR(40) NOT NULL,
    observed_bytes BIGINT,
    signature VARCHAR(64) NOT NULL,
    UNIQUE (run_id, kind, reference_id, reason)
);
CREATE INDEX storage_inspection_metadata_issues_page ON storage_inspection_metadata_issues (run_id, id);

CREATE TABLE storage_inspection_findings (
    id UUID PRIMARY KEY,
    identity VARCHAR(64) NOT NULL UNIQUE,
    type VARCHAR(32) NOT NULL,
    metadata_reason VARCHAR(40),
    reference_kind VARCHAR(16),
    reference_id VARCHAR(80),
    object_key TEXT,
    object_fingerprint VARCHAR(64),
    scope VARCHAR(8),
    owner_id BIGINT,
    severity VARCHAR(12) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('SUSPECTED','CONFIRMED','RESOLVED')),
    review_status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (review_status IN ('OPEN','ACKNOWLEDGED','IGNORED')),
    signature VARCHAR(64) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    stable_since TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_counted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_run UUID NOT NULL REFERENCES storage_inspection_runs(id),
    observations INTEGER NOT NULL DEFAULT 1,
    expected_bytes BIGINT,
    observed_bytes BIGINT,
    object_modified_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ
);
CREATE INDEX storage_inspection_findings_filter ON storage_inspection_findings (status, type, last_observed_at DESC);
CREATE INDEX storage_inspection_findings_scope ON storage_inspection_findings (scope, owner_id, last_observed_at DESC);

CREATE TABLE storage_inspection_reviews (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES storage_inspection_findings(id),
    actor_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('ACKNOWLEDGE','IGNORE','NOTE')),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX storage_inspection_reviews_finding ON storage_inspection_reviews (finding_id, created_at);

CREATE TABLE storage_quota_repair_previews (
    id UUID PRIMARY KEY,
    finding_id UUID NOT NULL REFERENCES storage_inspection_findings(id),
    actor_id BIGINT NOT NULL,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    recorded_bytes BIGINT NOT NULL,
    expected_bytes BIGINT NOT NULL,
    quota_revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '10 minutes'
);

CREATE TABLE storage_quota_repairs (
    id UUID PRIMARY KEY,
    preview_id UUID NOT NULL REFERENCES storage_quota_repair_previews(id),
    finding_id UUID NOT NULL REFERENCES storage_inspection_findings(id),
    actor_id BIGINT NOT NULL,
    idempotency_key UUID NOT NULL UNIQUE,
    scope VARCHAR(8) NOT NULL,
    owner_id BIGINT NOT NULL,
    used_before BIGINT NOT NULL,
    used_after BIGINT NOT NULL,
    quota_revision_before BIGINT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    rule_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
