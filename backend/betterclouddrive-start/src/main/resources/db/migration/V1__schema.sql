-- ============================================================
-- Better Cloud Drive - Consolidated Schema
-- PostgreSQL 16+
-- Fresh databases only. This file represents the final schema after V1-V14.
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

COMMENT ON TABLE users IS '用户表';
COMMENT ON COLUMN users.email_verified IS 'Whether the user has verified their email address';
COMMENT ON COLUMN users.role IS 'User role: ROLE_USER (default) or ROLE_ADMIN';
COMMENT ON COLUMN users.status IS '1=active, 0=disabled, -1=frozen';
COMMENT ON COLUMN users.storage_quota IS '存储配额（字节），默认10GB';
COMMENT ON COLUMN users.storage_used IS '已用存储（字节）';
COMMENT ON COLUMN users.webdav_enabled IS '是否启用 WebDAV，默认关闭';
COMMENT ON COLUMN users.webdav_password_hash IS 'WebDAV 独立密码 BCrypt 哈希';


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

COMMENT ON TABLE teams IS '团队表';
COMMENT ON COLUMN teams.storage_quota IS '团队独立存储配额（字节）';
COMMENT ON COLUMN teams.storage_used IS '团队已用存储（字节）';
COMMENT ON COLUMN teams.archived_at IS '团队归档时间，NULL 表示未归档';
COMMENT ON COLUMN teams.archived_by IS '归档操作管理员';
COMMENT ON COLUMN teams.deleted_by IS '删除团队的用户或管理员';
COMMENT ON COLUMN teams.purge_after IS '团队可被物理清理的时间';

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

COMMENT ON TABLE team_members IS '团队成员表';
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

COMMENT ON TABLE files IS '文件/文件夹表（邻接表树结构）';
COMMENT ON COLUMN files.parent_id IS 'NULL = 根目录';
COMMENT ON COLUMN files.team_id IS 'NULL=个人文件，非空=团队文件空间';
COMMENT ON COLUMN files.file_type IS 'file 或 folder';
COMMENT ON COLUMN files.storage_path IS '对象存储 key，格式: {userId}/{year}/{month}/{uuid}.{ext}';
COMMENT ON COLUMN files.md5_hash IS '文件MD5，用于秒传去重';
COMMENT ON COLUMN files.is_deleted IS 'FALSE=正常, TRUE=回收站';
COMMENT ON COLUMN files.version_count IS '当前版本数量';


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

COMMENT ON TABLE file_versions IS '文件版本表';
COMMENT ON COLUMN file_versions.version_number IS '版本号，从1开始递增';


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

COMMENT ON TABLE share_links IS '分享链接表';
COMMENT ON COLUMN share_links.share_code IS '8位随机短码，用于生成分享URL';
COMMENT ON COLUMN share_links.password_ciphertext IS '分享密码密文，使用 AES-GCM 加密；NULL 表示无密码';
COMMENT ON COLUMN share_links.max_visits IS '最大访问次数，NULL表示无限制';
COMMENT ON COLUMN share_links.team_id IS 'NULL=个人分享，非空=团队文件分享';


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

COMMENT ON TABLE upload_sessions IS '上传会话表（分片上传）';
COMMENT ON COLUMN upload_sessions.parent_id IS 'NULL = 根目录';
COMMENT ON COLUMN upload_sessions.team_id IS 'NULL=个人上传，非空=团队上传';
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

COMMENT ON TABLE folder_zip_download_cache IS '文件夹 ZIP 下载缓存表';


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

COMMENT ON TABLE operation_logs IS '操作日志表（按月分区）';
COMMENT ON COLUMN operation_logs.detail IS 'JSONB扩展字段，如 {"from":"/a.txt","to":"/b.txt"}';
COMMENT ON COLUMN operation_logs.request_id IS '单次 HTTP 请求 ID，用于 API 响应、运行日志和审计日志关联';
COMMENT ON COLUMN operation_logs.trace_id IS '分布式追踪 ID，用于关联 OpenTelemetry/Tempo trace';
COMMENT ON COLUMN operation_logs.status_code IS 'HTTP 状态码';
COMMENT ON COLUMN operation_logs.error_code IS '业务错误码，暂无业务响应解析时可为空';


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

COMMENT ON TABLE user_tokens IS 'JWT令牌表';
COMMENT ON COLUMN user_tokens.jti IS 'JWT ID，用于黑名单和追踪';


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

COMMENT ON TABLE favorites IS '收藏表';


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

COMMENT ON TABLE tags IS '标签表';
COMMENT ON COLUMN tags.team_id IS 'NULL=个人标签，非空=团队共享标签';


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

COMMENT ON TABLE file_tags IS '文件-标签关联表';
COMMENT ON COLUMN file_tags.team_id IS 'NULL=个人文件标签，非空=团队文件标签';


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

COMMENT ON TABLE team_invites IS '团队邀请表';
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

COMMENT ON TABLE team_operation_logs IS '团队审计日志';
COMMENT ON COLUMN team_operation_logs.team_id IS '团队 ID 快照；团队硬删除后仍保留审计日志用于追溯';

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    team_id     BIGINT,
    type        VARCHAR(64)  NOT NULL,
    title       VARCHAR(160) NOT NULL,
    content     TEXT,
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

COMMENT ON TABLE notifications IS '用户站内通知';

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

COMMENT ON TABLE team_file_permissions IS '团队文件/文件夹细粒度权限，子项继承最近父级配置';

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

COMMENT ON TABLE notification_preferences IS '用户通知类型偏好，未设置时默认开启';

CREATE TABLE team_event_outbox (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(32)  NOT NULL,
    aggregate_id   BIGINT,
    event_type     VARCHAR(96)  NOT NULL,
    actor_user_id  BIGINT,
    target_type    VARCHAR(64),
    target_id      BIGINT,
    payload        TEXT,
    status         VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    available_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at      TIMESTAMP,
    processed_at   TIMESTAMP,
    error_message  VARCHAR(512),
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

CREATE TABLE team_tasks (
    id                  BIGSERIAL PRIMARY KEY,
    team_id             BIGINT,
    actor_user_id       BIGINT,
    task_type           VARCHAR(64)  NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    target_type         VARCHAR(32),
    target_id           BIGINT,
    title               VARCHAR(200) NOT NULL,
    progress            INTEGER      NOT NULL DEFAULT 0,
    result_message      TEXT,
    error_message       TEXT,
    retry_count         INTEGER      NOT NULL DEFAULT 0,
    payload             TEXT,
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

COMMENT ON TABLE team_tasks IS '团队长任务状态中心';

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
