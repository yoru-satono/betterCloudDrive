-- ============================================================
-- Better Cloud Drive - Consolidated Schema
-- PostgreSQL 16+
-- Fresh databases only. This file is the complete current schema baseline.
-- ============================================================

-- ------------------------------------------------------------
-- Common trigger function for auto-updating updated_at
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- ============================================================
-- 1. users
-- ============================================================
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    email           VARCHAR(128),
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    preferred_locale VARCHAR(35) NOT NULL DEFAULT 'zh-Hans',
    nickname        VARCHAR(64),
    avatar_url      VARCHAR(512),
    role            VARCHAR(32)  NOT NULL DEFAULT 'ROLE_USER',
    status          INTEGER      NOT NULL DEFAULT 1,        -- 1=active, 0=disabled, -1=frozen
    storage_quota   BIGINT       NOT NULL DEFAULT 10737418240, -- 10 GB
    storage_used    BIGINT       NOT NULL DEFAULT 0,
    webdav_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
    webdav_password_hash VARCHAR(255),
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMP                               -- soft delete
);

CREATE UNIQUE INDEX uk_users_username ON users(username);
CREATE UNIQUE INDEX uk_users_email ON users(email) WHERE email IS NOT NULL;
CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_deleted_at ON users(deleted_at);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE users IS 'Users';
COMMENT ON COLUMN users.email_verified IS 'Whether the user has verified their email address';
COMMENT ON COLUMN users.preferred_locale IS 'Preferred UI locale as a canonical BCP 47 language tag';
COMMENT ON COLUMN users.role IS 'User role: ROLE_USER (default) or ROLE_ADMIN';
COMMENT ON COLUMN users.status IS '1=active, 0=disabled, -1=frozen';
COMMENT ON COLUMN users.storage_quota IS 'Storage quota in bytes; default 10 GB';
COMMENT ON COLUMN users.storage_used IS 'Used storage in bytes';
COMMENT ON COLUMN users.webdav_enabled IS 'Whether WebDAV is enabled; disabled by default';
COMMENT ON COLUMN users.webdav_password_hash IS 'BCrypt hash of the independent WebDAV password';


-- ============================================================
-- 2. teams and team_members
-- ============================================================
CREATE TABLE teams (
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(80) NOT NULL,
    owner_id       BIGINT      NOT NULL,
    storage_quota  BIGINT      NOT NULL DEFAULT 10737418240,
    storage_used   BIGINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at     TIMESTAMP,
    archived_at    TIMESTAMP,
    archived_by    BIGINT,
    deleted_by     BIGINT,
    purge_after    TIMESTAMP,

    CONSTRAINT fk_teams_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_teams_archived_by FOREIGN KEY (archived_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_teams_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_teams_owner ON teams(owner_id);
CREATE INDEX idx_teams_deleted_at ON teams(deleted_at);
CREATE INDEX idx_teams_archived_at ON teams(archived_at);
CREATE INDEX idx_teams_purge_after ON teams(purge_after) WHERE deleted_at IS NOT NULL;

CREATE TRIGGER trg_teams_updated_at
    BEFORE UPDATE ON teams
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE teams IS 'Teams';
COMMENT ON COLUMN teams.storage_quota IS 'Independent team storage quota in bytes';
COMMENT ON COLUMN teams.storage_used IS 'Team storage used in bytes';
COMMENT ON COLUMN teams.archived_at IS 'Archive time; NULL when active';
COMMENT ON COLUMN teams.archived_by IS 'Administrator that archived the team';
COMMENT ON COLUMN teams.deleted_by IS 'User or administrator that deleted the team';
COMMENT ON COLUMN teams.purge_after IS 'Time after which the team may be physically purged';

CREATE TABLE team_members (
    id          BIGSERIAL PRIMARY KEY,
    team_id     BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    role        VARCHAR(24) NOT NULL,
    status      INTEGER     NOT NULL DEFAULT 1,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_team_members_team_user ON team_members(team_id, user_id);
CREATE INDEX idx_team_members_user ON team_members(user_id);
CREATE INDEX idx_team_members_team_status ON team_members(team_id, status);

CREATE TRIGGER trg_team_members_updated_at
    BEFORE UPDATE ON team_members
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_members IS 'Team members';
COMMENT ON COLUMN team_members.role IS 'OWNER, ADMIN, EDITOR, VIEWER';
COMMENT ON COLUMN team_members.status IS '1=active, 0=removed';

-- ============================================================
-- 3. files (adjacency list tree: files + folders in one table)
-- ============================================================
CREATE TABLE files (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    team_id         BIGINT,
    parent_id       BIGINT,
    file_name       VARCHAR(255) NOT NULL,
    file_type       VARCHAR(32)  NOT NULL DEFAULT 'file',  -- 'file' | 'folder'
    mime_type       VARCHAR(128),
    file_size       BIGINT       NOT NULL DEFAULT 0,       -- bytes; 0 for folders
    storage_path    VARCHAR(512),                          -- SeaweedFS/S3 object key
    md5_hash        VARCHAR(64),                           -- instant upload dedup
    thumbnail_path  VARCHAR(512),                          -- thumbnail S3 key
    is_deleted      BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at      TIMESTAMP,
    version_count   INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_files_user   FOREIGN KEY (user_id)   REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_files_team   FOREIGN KEY (team_id)   REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_files_parent FOREIGN KEY (parent_id) REFERENCES files(id) ON DELETE CASCADE
);

-- Directory listing (most frequent query)
CREATE INDEX idx_files_user_parent ON files(user_id, parent_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_files_personal_user_parent ON files(user_id, parent_id) WHERE team_id IS NULL AND is_deleted = FALSE;
CREATE INDEX idx_files_team_parent_listing ON files(team_id, parent_id, file_type, file_name)
    WHERE team_id IS NOT NULL AND is_deleted = FALSE;
CREATE INDEX idx_files_team_deleted ON files(team_id, is_deleted) WHERE team_id IS NOT NULL;
-- Instant upload dedup
CREATE INDEX idx_files_md5 ON files(md5_hash) WHERE md5_hash IS NOT NULL AND is_deleted = FALSE;
-- Recycle bin cleanup
CREATE INDEX idx_files_deleted ON files(is_deleted, deleted_at) WHERE is_deleted = TRUE;
-- User recycle bin list
CREATE INDEX idx_files_user_deleted ON files(user_id, is_deleted);
-- Filter by type
CREATE INDEX idx_files_extension ON files(user_id, file_type);

CREATE TRIGGER trg_files_updated_at
    BEFORE UPDATE ON files
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE files IS 'Files and folders stored as an adjacency-list tree';
COMMENT ON COLUMN files.parent_id IS 'NULL denotes a root item';
COMMENT ON COLUMN files.team_id IS 'NULL for personal files; non-NULL for team files';
COMMENT ON COLUMN files.file_type IS 'file or folder';
COMMENT ON COLUMN files.storage_path IS 'Object storage key: {userId}/{year}/{month}/{uuid}.{ext}';
COMMENT ON COLUMN files.md5_hash IS 'File MD5 used for instant-upload deduplication';
COMMENT ON COLUMN files.is_deleted IS 'FALSE for active items; TRUE for recycle bin items';
COMMENT ON COLUMN files.version_count IS 'Current version count';


-- ============================================================
-- 4. file_versions
-- ============================================================
CREATE TABLE file_versions (
    id              BIGSERIAL PRIMARY KEY,
    file_id         BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    version_number  INT          NOT NULL,
    file_size       BIGINT       NOT NULL,
    md5_hash        VARCHAR(64)  NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_versions_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_versions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_file_version ON file_versions(file_id, version_number);
CREATE INDEX idx_file_versions_file ON file_versions(file_id);
CREATE INDEX idx_file_versions_user ON file_versions(user_id);

COMMENT ON TABLE file_versions IS 'File versions';
COMMENT ON COLUMN file_versions.version_number IS 'Version number starting at 1';


-- ============================================================
-- 5. share_links
-- ============================================================
CREATE TABLE share_links (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    team_id         BIGINT,
    file_id         BIGINT       NOT NULL,
    share_code      VARCHAR(16)  NOT NULL,
    password_ciphertext VARCHAR(255),                         -- AES-GCM ciphertext; NULL = no password
    expire_at       TIMESTAMP,                               -- NULL = never expires
    max_visits      INT,                                     -- NULL = unlimited
    download_count  INT          NOT NULL DEFAULT 0,
    visit_count     INT          NOT NULL DEFAULT 0,         -- synced from Redis
    is_canceled     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_share_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_links_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_share_code ON share_links(share_code);
CREATE INDEX idx_share_user ON share_links(user_id);
CREATE INDEX idx_share_file ON share_links(file_id);
CREATE INDEX idx_share_expire ON share_links(expire_at) WHERE expire_at IS NOT NULL AND is_canceled = FALSE;
CREATE INDEX idx_share_links_team ON share_links(team_id, is_canceled, created_at DESC) WHERE team_id IS NOT NULL;
CREATE INDEX idx_share_links_user_personal ON share_links(user_id, is_canceled, created_at DESC) WHERE team_id IS NULL;

CREATE TRIGGER trg_share_links_updated_at
    BEFORE UPDATE ON share_links
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE share_links IS 'Share links';
COMMENT ON COLUMN share_links.share_code IS 'Eight-character random code used in share URLs';
COMMENT ON COLUMN share_links.password_ciphertext IS 'AES-GCM encrypted share password; NULL means no password';
COMMENT ON COLUMN share_links.max_visits IS 'Maximum visits; NULL means unlimited';
COMMENT ON COLUMN share_links.team_id IS 'NULL for personal shares; non-NULL for team shares';


-- ============================================================
-- 6. upload_sessions
-- ============================================================
CREATE TABLE upload_sessions (
    id              VARCHAR(64)  PRIMARY KEY,               -- UUID
    user_id         BIGINT       NOT NULL,
    team_id         BIGINT,
    parent_id       BIGINT,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT       NOT NULL,
    md5_hash        VARCHAR(64),
    chunk_size      INT          NOT NULL DEFAULT 5242880,   -- 5 MB
    total_chunks    INT          NOT NULL,
    received_chunks INT          NOT NULL DEFAULT 0,
    storage_path    VARCHAR(512),                            -- SeaweedFS multipart upload ID
    status          INTEGER      NOT NULL DEFAULT 1,         -- 1=uploading, 2=completed, 3=expired
    completed_file_id BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_upload_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_upload_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_upload_completed_file FOREIGN KEY (completed_file_id) REFERENCES files(id) ON DELETE SET NULL
);

CREATE INDEX idx_upload_sessions_status ON upload_sessions(status, created_at);
CREATE INDEX idx_upload_sessions_user ON upload_sessions(user_id);
CREATE INDEX idx_upload_sessions_team ON upload_sessions(team_id) WHERE team_id IS NOT NULL;
CREATE INDEX idx_upload_sessions_completed_file ON upload_sessions(completed_file_id) WHERE completed_file_id IS NOT NULL;

CREATE TRIGGER trg_upload_sessions_updated_at
    BEFORE UPDATE ON upload_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE upload_sessions IS 'Multipart upload sessions';
COMMENT ON COLUMN upload_sessions.parent_id IS 'NULL denotes the root folder';
COMMENT ON COLUMN upload_sessions.team_id IS 'NULL for personal uploads; non-NULL for team uploads';
COMMENT ON COLUMN upload_sessions.status IS '1=uploading, 2=completed, 3=expired';
COMMENT ON COLUMN upload_sessions.completed_file_id IS 'File created by a completed upload session; used for idempotent complete retries';


-- ============================================================
-- 6. folder_zip_download_cache
-- ============================================================
CREATE TABLE folder_zip_download_cache (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT       NOT NULL,
    folder_id           BIGINT       NOT NULL,
    object_key          VARCHAR(512) NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    file_size           BIGINT       NOT NULL DEFAULT 0,
    content_signature   VARCHAR(64)  NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_downloaded_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_folder_zip_cache_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_folder_zip_cache_folder FOREIGN KEY (folder_id) REFERENCES files(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_folder_zip_cache_user_folder
    ON folder_zip_download_cache(user_id, folder_id);

CREATE INDEX idx_folder_zip_cache_last_downloaded
    ON folder_zip_download_cache(last_downloaded_at);

COMMENT ON TABLE folder_zip_download_cache IS 'Folder ZIP download cache';


-- ============================================================
-- 7. operation_logs (partitioned by month)
-- ============================================================
CREATE TABLE operation_logs (
    id            BIGSERIAL,
    user_id       BIGINT,
    action_type   VARCHAR(32)  NOT NULL,   -- UPLOAD, DOWNLOAD, DELETE, MOVE, COPY, RENAME, SHARE, RESTORE
    target_type   VARCHAR(32)  NOT NULL,   -- FILE, FOLDER, SHARE_LINK, USER
    target_id     BIGINT,
    detail        JSONB,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(512),
    result        INTEGER      NOT NULL DEFAULT 1,  -- 1=success, 0=failure
    duration_ms   INT,
    request_id    VARCHAR(64),
    trace_id      VARCHAR(64),
    status_code   INT,
    error_code    INT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Monthly partitions (pre-created for 2026)
CREATE TABLE operation_logs_202601 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
CREATE TABLE operation_logs_202602 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-02-01') TO ('2026-03-01');
CREATE TABLE operation_logs_202603 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-03-01') TO ('2026-04-01');
CREATE TABLE operation_logs_202604 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE operation_logs_202605 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE operation_logs_202606 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE operation_logs_202607 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE operation_logs_202608 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE operation_logs_202609 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE operation_logs_202610 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE operation_logs_202611 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE operation_logs_202612 PARTITION OF operation_logs
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
-- Default partition for future data
CREATE TABLE operation_logs_default PARTITION OF operation_logs DEFAULT;

CREATE INDEX idx_oplog_user ON operation_logs(user_id);
CREATE INDEX idx_oplog_action ON operation_logs(action_type, created_at);
CREATE INDEX idx_oplog_created ON operation_logs(created_at);
CREATE INDEX idx_oplog_request_id ON operation_logs(request_id);
CREATE INDEX idx_oplog_trace_id ON operation_logs(trace_id);
CREATE INDEX idx_oplog_status_created ON operation_logs(status_code, created_at);

COMMENT ON TABLE operation_logs IS 'Monthly partitioned operation logs';
COMMENT ON COLUMN operation_logs.detail IS 'Extensible JSONB data, for example {"from":"/a.txt","to":"/b.txt"}';
COMMENT ON COLUMN operation_logs.request_id IS 'HTTP request ID correlating API responses, runtime logs, and audit logs';
COMMENT ON COLUMN operation_logs.trace_id IS 'Trace ID for request, runtime log, and audit correlation';
COMMENT ON COLUMN operation_logs.status_code IS 'HTTP status code';
COMMENT ON COLUMN operation_logs.error_code IS 'Business error code; nullable when no business response was parsed';


-- ============================================================
-- Partition management functions
-- ============================================================

-- Create next month's partition (called by scheduler or pg_cron)
CREATE OR REPLACE FUNCTION create_monthly_partition()
RETURNS void AS $$
DECLARE
    next_month_start DATE := date_trunc('month', CURRENT_DATE) + INTERVAL '1 month';
    next_month_end   DATE := next_month_start + INTERVAL '1 month';
    partition_name   TEXT := 'operation_logs_' || to_char(next_month_start, 'YYYYMM');
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF operation_logs FOR VALUES FROM (%L) TO (%L)',
        partition_name, next_month_start, next_month_end
    );
END;
$$ LANGUAGE plpgsql;

-- Drop partitions older than retention_months
CREATE OR REPLACE FUNCTION drop_old_partitions(retention_months INT DEFAULT 6)
RETURNS void AS $$
DECLARE
    cutoff_date DATE := date_trunc('month', CURRENT_DATE) - (retention_months || ' months')::INTERVAL;
    rec         RECORD;
BEGIN
    FOR rec IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tablename LIKE 'operation_logs_%'
          AND tablename ~ '^operation_logs_\d{6}$'
    LOOP
        IF to_date(substring(rec.tablename from '\d{6}$'), 'YYYYMM') < cutoff_date THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', rec.tablename);
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;


-- ============================================================
-- 8. user_tokens
-- ============================================================
CREATE TABLE user_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    jti         VARCHAR(64)  NOT NULL,                    -- JWT ID
    token_type  VARCHAR(16)  NOT NULL DEFAULT 'access',   -- 'access' | 'refresh'
    issued_at   TIMESTAMP    NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    is_revoked  BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT fk_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_user_tokens_jti ON user_tokens(jti);
CREATE INDEX idx_user_tokens_user ON user_tokens(user_id);
CREATE INDEX idx_user_tokens_expires ON user_tokens(expires_at);

COMMENT ON TABLE user_tokens IS 'JWT tokens';
COMMENT ON COLUMN user_tokens.jti IS 'JWT ID used for revocation and tracing';


-- ============================================================
-- 9. favorites
-- ============================================================
CREATE TABLE favorites (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    file_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fav_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_fav_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_fav_user_file ON favorites(user_id, file_id);

COMMENT ON TABLE favorites IS 'Favorites';


-- ============================================================
-- 10. tags
-- ============================================================
CREATE TABLE tags (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    team_id    BIGINT,
    tag_name   VARCHAR(64) NOT NULL,
    color      VARCHAR(7)  DEFAULT '#1890ff',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tags_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_tags_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_tags_personal_user_tag ON tags(user_id, tag_name) WHERE team_id IS NULL;
CREATE UNIQUE INDEX uk_tags_team_tag ON tags(team_id, tag_name) WHERE team_id IS NOT NULL;
CREATE INDEX idx_tags_user ON tags(user_id);
CREATE INDEX idx_tags_team ON tags(team_id, tag_name) WHERE team_id IS NOT NULL;

COMMENT ON TABLE tags IS 'Tags';
COMMENT ON COLUMN tags.team_id IS 'NULL for personal tags; non-NULL for shared team tags';


-- ============================================================
-- 11. file_tags
-- ============================================================
CREATE TABLE file_tags (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    team_id    BIGINT,
    file_id    BIGINT    NOT NULL,
    tag_id     BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ft_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_tag  FOREIGN KEY (tag_id)  REFERENCES tags(id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_file_tags_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_file_tag ON file_tags(file_id, tag_id);
CREATE INDEX idx_file_tags_tag ON file_tags(tag_id);
CREATE INDEX idx_file_tags_team_tag ON file_tags(team_id, tag_id) WHERE team_id IS NOT NULL;

COMMENT ON TABLE file_tags IS 'File-to-tag associations';
COMMENT ON COLUMN file_tags.team_id IS 'NULL for personal file tags; non-NULL for team file tags';


-- ============================================================
-- Team collaboration, notifications, tasks, and async processing
-- ============================================================
CREATE TABLE team_invites (
    id                  BIGSERIAL PRIMARY KEY,
    team_id             BIGINT       NOT NULL,
    email               VARCHAR(255) NOT NULL,
    role                VARCHAR(24)  NOT NULL,
    token               VARCHAR(64)  NOT NULL,
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    invited_by_user_id  BIGINT       NOT NULL,
    accepted_by_user_id BIGINT,
    expires_at          TIMESTAMP    NOT NULL,
    accepted_at         TIMESTAMP,
    rejected_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_team_invites_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_invites_inviter FOREIGN KEY (invited_by_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_invites_acceptor FOREIGN KEY (accepted_by_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_team_invites_token ON team_invites(token);
CREATE INDEX idx_team_invites_team_status ON team_invites(team_id, status, created_at DESC);
CREATE INDEX idx_team_invites_email_status ON team_invites(email, status);

CREATE TRIGGER trg_team_invites_updated_at
    BEFORE UPDATE ON team_invites
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_invites IS 'Team invitations';
COMMENT ON COLUMN team_invites.status IS 'PENDING, ACCEPTED, REJECTED, CANCELED, EXPIRED';

CREATE TABLE team_operation_logs (
    id            BIGSERIAL PRIMARY KEY,
    team_id       BIGINT       NOT NULL,
    actor_user_id BIGINT,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64),
    target_id     BIGINT,
    result        INTEGER      NOT NULL DEFAULT 1,
    status_code   INTEGER,
    request_id    VARCHAR(128),
    trace_id      VARCHAR(128),
    details       TEXT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_team_operation_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_team_operation_logs_team_created ON team_operation_logs(team_id, created_at DESC);
CREATE INDEX idx_team_operation_logs_actor ON team_operation_logs(actor_user_id);
CREATE INDEX idx_team_operation_logs_action ON team_operation_logs(action);
CREATE INDEX idx_team_operation_logs_target ON team_operation_logs(target_type, target_id);
CREATE INDEX idx_team_operation_logs_team_actor_created ON team_operation_logs(team_id, actor_user_id, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_action_created ON team_operation_logs(team_id, action, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_target_type_created ON team_operation_logs(team_id, target_type, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_result_created ON team_operation_logs(team_id, result, created_at DESC);

COMMENT ON TABLE team_operation_logs IS 'Team audit log';
COMMENT ON COLUMN team_operation_logs.team_id IS 'Team ID snapshot retained for audit after hard deletion';

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    team_id     BIGINT,
    type        VARCHAR(64)  NOT NULL,
    title_key       VARCHAR(160) NOT NULL,
    content_key     VARCHAR(160) NOT NULL,
    message_params JSONB        NOT NULL DEFAULT '{}'::jsonb,
    link        VARCHAR(512),
    read_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE SET NULL
);

CREATE INDEX idx_notifications_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread_created ON notifications(user_id, created_at DESC) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_team_type ON notifications(team_id, type, created_at DESC);
CREATE INDEX idx_notifications_user_type_created ON notifications(user_id, type, created_at DESC);

COMMENT ON TABLE notifications IS 'In-app user notifications with locale-neutral message keys';

CREATE TABLE team_file_permissions (
    id              BIGSERIAL PRIMARY KEY,
    team_id          BIGINT      NOT NULL,
    file_id          BIGINT      NOT NULL,
    subject_type     VARCHAR(16) NOT NULL,
    subject_user_id  BIGINT,
    subject_role     VARCHAR(24),
    can_read         BOOLEAN     NOT NULL DEFAULT FALSE,
    can_write        BOOLEAN     NOT NULL DEFAULT FALSE,
    can_share        BOOLEAN     NOT NULL DEFAULT FALSE,
    can_delete       BOOLEAN     NOT NULL DEFAULT FALSE,
    can_manage       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by       BIGINT,
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_team_file_permissions_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_file_permissions_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_file_permissions_subject_user FOREIGN KEY (subject_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_file_permissions_created_by FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT ck_team_file_permissions_subject CHECK (
        (subject_type = 'USER' AND subject_user_id IS NOT NULL AND subject_role IS NULL)
        OR (subject_type = 'ROLE' AND subject_user_id IS NULL AND subject_role IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_team_file_permissions_user
    ON team_file_permissions(team_id, file_id, subject_user_id) WHERE subject_type = 'USER';
CREATE UNIQUE INDEX uk_team_file_permissions_role
    ON team_file_permissions(team_id, file_id, subject_role) WHERE subject_type = 'ROLE';
CREATE INDEX idx_team_file_permissions_file ON team_file_permissions(team_id, file_id);

CREATE TRIGGER trg_team_file_permissions_updated_at
    BEFORE UPDATE ON team_file_permissions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_file_permissions IS 'Fine-grained team file permissions inherited from the nearest configured ancestor';

CREATE TABLE notification_preferences (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL,
    notification_type   VARCHAR(64) NOT NULL,
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_preferences_user_type UNIQUE (user_id, notification_type)
);

CREATE INDEX idx_notification_preferences_user ON notification_preferences(user_id);

CREATE TRIGGER trg_notification_preferences_updated_at
    BEFORE UPDATE ON notification_preferences
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE notification_preferences IS 'User notification preferences; enabled by default when unset';

CREATE TABLE team_event_outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   BIGINT,
    event_type     VARCHAR(96)  NOT NULL,
    actor_user_id  BIGINT,
    target_type    VARCHAR(64),
    target_id      BIGINT,
    payload        TEXT,
    message_key    VARCHAR(200),
    message_params JSONB        NOT NULL DEFAULT '{}'::jsonb,
    traceparent    VARCHAR(55),
    tracestate     VARCHAR(512),
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    available_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at      TIMESTAMP,
    processed_at   TIMESTAMP,
    last_error     VARCHAR(512),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_team_event_outbox_pending ON team_event_outbox(status, available_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_team_event_outbox_aggregate ON team_event_outbox(aggregate_type, aggregate_id, created_at DESC);
CREATE INDEX idx_team_event_outbox_event_type ON team_event_outbox(event_type, created_at DESC);

CREATE TRIGGER trg_team_event_outbox_updated_at
    BEFORE UPDATE ON team_event_outbox
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_event_outbox IS 'Team-domain event outbox for future asynchronous workers';
COMMENT ON COLUMN team_event_outbox.aggregate_type IS 'TEAM, NOTIFICATION, or future aggregate category';
COMMENT ON COLUMN team_event_outbox.status IS 'PENDING, PROCESSING, PROCESSED, FAILED';
COMMENT ON COLUMN team_event_outbox.traceparent IS 'W3C trace context captured when the durable event was created';
COMMENT ON COLUMN team_event_outbox.tracestate IS 'Optional W3C vendor trace state for the durable event';

CREATE TABLE team_tasks (
    id                  BIGSERIAL PRIMARY KEY,
    team_id             BIGINT,
    actor_user_id       BIGINT,
    task_type           VARCHAR(64)  NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    target_type         VARCHAR(32),
    target_id           BIGINT,
    title_key           VARCHAR(200) NOT NULL,
    title_params        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    progress            INTEGER      NOT NULL DEFAULT 0,
    result_message_key    VARCHAR(200),
    result_message_params JSONB      NOT NULL DEFAULT '{}'::jsonb,
    error_message_key     VARCHAR(200),
    error_message_params  JSONB      NOT NULL DEFAULT '{}'::jsonb,
    retry_count         INTEGER      NOT NULL DEFAULT 0,
    payload             TEXT,
    traceparent         VARCHAR(55),
    tracestate          VARCHAR(512),
    result_path         VARCHAR(1024),
    result_file_name    VARCHAR(255),
    result_content_type VARCHAR(128),
    result_size_bytes   BIGINT,
    result_expires_at   TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,

    CONSTRAINT fk_team_tasks_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE SET NULL,
    CONSTRAINT fk_team_tasks_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_team_tasks_team_created ON team_tasks(team_id, created_at DESC);
CREATE INDEX idx_team_tasks_team_status ON team_tasks(team_id, status, created_at DESC);
CREATE INDEX idx_team_tasks_type_status ON team_tasks(task_type, status, created_at DESC);
CREATE INDEX idx_team_tasks_pending_type_created ON team_tasks(task_type, status, created_at ASC);
CREATE INDEX idx_team_tasks_result_expires ON team_tasks(result_expires_at) WHERE result_expires_at IS NOT NULL;

CREATE TRIGGER trg_team_tasks_updated_at
    BEFORE UPDATE ON team_tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_tasks IS 'Team long-running task status center';
COMMENT ON COLUMN team_tasks.traceparent IS 'W3C trace context linked by delayed task execution';
COMMENT ON COLUMN team_tasks.tracestate IS 'Optional W3C vendor trace state for delayed task execution';

CREATE TABLE onlyoffice_callbacks (
    id               BIGSERIAL PRIMARY KEY,
    team_id          BIGINT       NOT NULL,
    file_id          BIGINT       NOT NULL,
    callback_key     VARCHAR(128) NOT NULL,
    status           INT          NOT NULL,
    content_url_hash VARCHAR(64)  NOT NULL,
    state            INT          NOT NULL DEFAULT 1,
    version_id       BIGINT,
    error_message    VARCHAR(512),
    actor_user_id    BIGINT,
    download_url     TEXT,
    task_id          BIGINT,
    last_attempt_at  TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_onlyoffice_callbacks_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_onlyoffice_callbacks_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_onlyoffice_callbacks_version FOREIGN KEY (version_id) REFERENCES file_versions(id) ON DELETE SET NULL,
    CONSTRAINT fk_onlyoffice_callbacks_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT fk_onlyoffice_callbacks_task FOREIGN KEY (task_id) REFERENCES team_tasks(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_onlyoffice_callback_save
    ON onlyoffice_callbacks(team_id, file_id, callback_key, status, content_url_hash);
CREATE INDEX idx_onlyoffice_callbacks_file ON onlyoffice_callbacks(file_id, created_at DESC);
CREATE INDEX idx_onlyoffice_callbacks_state_created ON onlyoffice_callbacks(state, created_at ASC);
CREATE INDEX idx_onlyoffice_callbacks_task ON onlyoffice_callbacks(task_id);

COMMENT ON TABLE onlyoffice_callbacks IS 'OnlyOffice save callback idempotency records';
COMMENT ON COLUMN onlyoffice_callbacks.callback_key IS 'OnlyOffice document key from the callback payload';
COMMENT ON COLUMN onlyoffice_callbacks.content_url_hash IS 'SHA-256 of callback download URL; separates real later saves from duplicate delivery';
COMMENT ON COLUMN onlyoffice_callbacks.actor_user_id IS 'User that opened the editable OnlyOffice session';
COMMENT ON COLUMN onlyoffice_callbacks.download_url IS 'Resolved document server download URL for asynchronous save processing';
COMMENT ON COLUMN onlyoffice_callbacks.task_id IS 'Linked team task that exposes save progress and retry state';
COMMENT ON COLUMN onlyoffice_callbacks.last_attempt_at IS 'Last background save attempt timestamp';
COMMENT ON COLUMN onlyoffice_callbacks.state IS '1=pending, 2=saved, 3=failed';

-- ============================================================
-- Dead-letter management
-- ============================================================
CREATE TABLE dead_letter_records (
    id                  UUID PRIMARY KEY,
    original_message_id VARCHAR(128) NOT NULL,
    original_queue      VARCHAR(32)  NOT NULL,
    routing_key         VARCHAR(64)  NOT NULL,
    task_type           VARCHAR(64)  NOT NULL,
    failure_category    VARCHAR(32)  NOT NULL,
    delivery_count      INTEGER      NOT NULL DEFAULT 1,
    first_failed_at     TIMESTAMP    NOT NULL,
    last_failed_at      TIMESTAMP    NOT NULL,
    request_id          VARCHAR(128),
    trace_id            VARCHAR(32),
    traceparent         VARCHAR(55),
    tracestate          VARCHAR(512),
    test_marker         VARCHAR(128),
    payload             TEXT         NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    replay_token        UUID,
    resolution_reason   VARCHAR(500),
    resolved_by         BIGINT,
    resolved_at         TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_dead_letter_original_message UNIQUE (original_message_id),
    CONSTRAINT ck_dead_letter_queue CHECK (original_queue IN ('notifications', 'team-tasks', 'unknown')),
    CONSTRAINT ck_dead_letter_category CHECK (failure_category IN ('invalid-payload', 'missing-record', 'delivery-exhausted', 'unknown')),
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('OPEN', 'REPLAYING', 'REPLAYED', 'DISCARDED'))
);

CREATE INDEX idx_dead_letter_open_failed
    ON dead_letter_records(status, last_failed_at DESC) WHERE status = 'OPEN';
CREATE INDEX idx_dead_letter_source
    ON dead_letter_records(original_queue, failure_category, status);

CREATE TRIGGER trg_dead_letter_records_updated_at
    BEFORE UPDATE ON dead_letter_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE dead_letter_records IS 'Queryable, auditable dead-letter control records; payload is never exposed by APIs';
COMMENT ON COLUMN dead_letter_records.replay_token IS 'Idempotency token for a single accepted replay transition';
COMMENT ON COLUMN dead_letter_records.test_marker IS 'Explicit bcd-e2e marker used by scoped automated cleanup only';
