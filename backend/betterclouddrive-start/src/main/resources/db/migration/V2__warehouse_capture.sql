-- Durable, credential-free business facts. Export state is deliberately separate
-- from event identity: sequence watermarks cannot see transactions committing late.
CREATE TABLE warehouse_events (
    event_id UUID PRIMARY KEY,
    deduplication_key VARCHAR(192) NOT NULL UNIQUE,
    event_type VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id BIGINT,
    team_id BIGINT,
    resource_id BIGINT,
    attempt_id UUID,
    channel VARCHAR(16) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    byte_count BIGINT NOT NULL DEFAULT 0 CHECK (byte_count >= 0),
    schema_version INTEGER NOT NULL DEFAULT 1 CHECK (schema_version = 1),
    export_batch_id UUID,
    export_lease_until TIMESTAMPTZ,
    exported_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT warehouse_events_channel CHECK (channel IN ('API', 'WEBDAV')),
    CONSTRAINT warehouse_events_mode CHECK (mode IN ('NONE', 'MULTIPART', 'INSTANT', 'STREAM', 'FILE', 'VERSION', 'ZIP', 'SHARE')),
    CONSTRAINT warehouse_events_outcome CHECK (outcome IN ('SUCCEEDED', 'STARTED', 'FAILED')),
    CONSTRAINT warehouse_events_type CHECK (event_type IN (
        'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'USER_ACTIVITY', 'UPLOAD_COMMITTED',
        'DOWNLOAD_STARTED', 'DOWNLOAD_FINISHED', 'SHARE_CREATED', 'SHARE_CANCELED', 'SHARE_ACCESSED'))
);
CREATE INDEX idx_warehouse_events_pending ON warehouse_events (created_at, event_id) WHERE exported_at IS NULL;
CREATE INDEX idx_warehouse_events_exported ON warehouse_events (exported_at) WHERE exported_at IS NOT NULL;
CREATE INDEX idx_warehouse_events_batch ON warehouse_events (export_batch_id) WHERE exported_at IS NULL;

-- Explicit projections ensure the extractor never needs access to credentials,
-- names, object paths, request/audit payloads, or share codes.
CREATE VIEW warehouse_source_users AS
SELECT id, status, storage_quota, created_at, deleted_at FROM users;
CREATE VIEW warehouse_source_teams AS
SELECT id, storage_quota, created_at, deleted_at, archived_at FROM teams;
CREATE VIEW warehouse_source_members AS
SELECT team_id, user_id, role, status FROM team_members;
CREATE VIEW warehouse_source_files AS
SELECT id, user_id, team_id, parent_id, file_type, file_size, is_deleted, created_at, deleted_at FROM files;
CREATE VIEW warehouse_source_versions AS
SELECT v.id, v.file_id, f.user_id, f.team_id, v.file_size, v.created_at
FROM file_versions v JOIN files f ON f.id = v.file_id;
CREATE VIEW warehouse_source_shares AS
SELECT id, user_id, team_id, file_id, is_canceled, expire_at, created_at FROM share_links;
