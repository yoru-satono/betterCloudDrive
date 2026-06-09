-- ============================================================
-- Better Cloud Drive - Initial Schema (MVP)
-- PostgreSQL 16+
-- 10 tables: users, files, file_versions, share_links,
--            upload_sessions, operation_logs (partitioned),
--            user_tokens, favorites, tags, file_tags
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
    phone           VARCHAR(20),
    nickname        VARCHAR(64),
    avatar_url      VARCHAR(512),
    status          SMALLINT     NOT NULL DEFAULT 1,        -- 1=active, 0=disabled, -1=frozen
    storage_quota   BIGINT       NOT NULL DEFAULT 10737418240, -- 10 GB
    storage_used    BIGINT       NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN users.status IS '1=active, 0=disabled, -1=frozen';
COMMENT ON COLUMN users.storage_quota IS '存储配额（字节），默认10GB';
COMMENT ON COLUMN users.storage_used IS '已用存储（字节）';


-- ============================================================
-- 2. files (adjacency list tree: files + folders in one table)
-- ============================================================
CREATE TABLE files (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
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
    CONSTRAINT fk_files_parent FOREIGN KEY (parent_id) REFERENCES files(id) ON DELETE CASCADE
);

-- Directory listing (most frequent query)
CREATE INDEX idx_files_user_parent ON files(user_id, parent_id) WHERE is_deleted = FALSE;
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
COMMENT ON COLUMN files.file_type IS 'file 或 folder';
COMMENT ON COLUMN files.storage_path IS '对象存储 key，格式: {userId}/{year}/{month}/{uuid}.{ext}';
COMMENT ON COLUMN files.md5_hash IS '文件MD5，用于秒传去重';
COMMENT ON COLUMN files.is_deleted IS 'FALSE=正常, TRUE=回收站';
COMMENT ON COLUMN files.version_count IS '当前版本数量';


-- ============================================================
-- 3. file_versions
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
-- 4. share_links
-- ============================================================
CREATE TABLE share_links (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    file_id         BIGINT       NOT NULL,
    share_code      VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(255),                            -- BCrypt; NULL = no password
    expire_at       TIMESTAMP,                               -- NULL = never expires
    max_downloads   INT,                                     -- NULL = unlimited
    download_count  INT          NOT NULL DEFAULT 0,
    visit_count     INT          NOT NULL DEFAULT 0,         -- synced from Redis
    is_canceled     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_share_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_share_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_share_code ON share_links(share_code);
CREATE INDEX idx_share_user ON share_links(user_id);
CREATE INDEX idx_share_file ON share_links(file_id);
CREATE INDEX idx_share_expire ON share_links(expire_at) WHERE expire_at IS NOT NULL AND is_canceled = FALSE;

CREATE TRIGGER trg_share_links_updated_at
    BEFORE UPDATE ON share_links
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE share_links IS '分享链接表';
COMMENT ON COLUMN share_links.share_code IS '8位随机短码，用于生成分享URL';
COMMENT ON COLUMN share_links.password_hash IS 'BCrypt哈希，NULL表示无密码';


-- ============================================================
-- 5. upload_sessions
-- ============================================================
CREATE TABLE upload_sessions (
    id              VARCHAR(64)  PRIMARY KEY,               -- UUID
    user_id         BIGINT       NOT NULL,
    parent_id       BIGINT       NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT       NOT NULL,
    md5_hash        VARCHAR(64),
    chunk_size      INT          NOT NULL DEFAULT 5242880,   -- 5 MB
    total_chunks    INT          NOT NULL,
    received_chunks INT          NOT NULL DEFAULT 0,
    storage_path    VARCHAR(512),                            -- SeaweedFS multipart upload ID
    status          SMALLINT     NOT NULL DEFAULT 1,         -- 1=uploading, 2=completed, 3=expired
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_upload_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_upload_sessions_status ON upload_sessions(status, created_at);
CREATE INDEX idx_upload_sessions_user ON upload_sessions(user_id);

CREATE TRIGGER trg_upload_sessions_updated_at
    BEFORE UPDATE ON upload_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE upload_sessions IS '上传会话表（分片上传）';
COMMENT ON COLUMN upload_sessions.status IS '1=uploading, 2=completed, 3=expired';


-- ============================================================
-- 6. operation_logs (partitioned by month)
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
    result        SMALLINT     NOT NULL DEFAULT 1,  -- 1=success, 0=failure
    duration_ms   INT,
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

COMMENT ON TABLE operation_logs IS '操作日志表（按月分区）';
COMMENT ON COLUMN operation_logs.detail IS 'JSONB扩展字段，如 {"from":"/a.txt","to":"/b.txt"}';


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
-- 7. user_tokens
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
-- 8. favorites
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
-- 9. tags
-- ============================================================
CREATE TABLE tags (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    tag_name   VARCHAR(64) NOT NULL,
    color      VARCHAR(7)  DEFAULT '#1890ff',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_tags_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_tags_user_tag ON tags(user_id, tag_name);
CREATE INDEX idx_tags_user ON tags(user_id);

COMMENT ON TABLE tags IS '标签表';


-- ============================================================
-- 10. file_tags
-- ============================================================
CREATE TABLE file_tags (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    file_id    BIGINT    NOT NULL,
    tag_id     BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ft_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_tag  FOREIGN KEY (tag_id)  REFERENCES tags(id) ON DELETE CASCADE,
    CONSTRAINT fk_ft_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uk_file_tag ON file_tags(file_id, tag_id);
CREATE INDEX idx_file_tags_tag ON file_tags(tag_id);

COMMENT ON TABLE file_tags IS '文件-标签关联表';
