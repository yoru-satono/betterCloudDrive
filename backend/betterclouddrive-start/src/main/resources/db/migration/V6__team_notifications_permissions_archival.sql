-- ============================================================
-- Team notifications, inherited file permissions, and archive metadata
-- ============================================================

ALTER TABLE teams
    ADD COLUMN archived_at TIMESTAMP,
    ADD COLUMN archived_by BIGINT,
    ADD COLUMN deleted_by BIGINT,
    ADD COLUMN purge_after TIMESTAMP;

ALTER TABLE teams
    ADD CONSTRAINT fk_teams_archived_by FOREIGN KEY (archived_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_teams_deleted_by FOREIGN KEY (deleted_by) REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_teams_archived_at ON teams(archived_at);
CREATE INDEX idx_teams_purge_after ON teams(purge_after) WHERE deleted_at IS NOT NULL;

COMMENT ON COLUMN teams.archived_at IS '团队归档时间，NULL 表示未归档';
COMMENT ON COLUMN teams.archived_by IS '归档操作管理员';
COMMENT ON COLUMN teams.deleted_by IS '删除团队的用户或管理员';
COMMENT ON COLUMN teams.purge_after IS '团队可被物理清理的时间';

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
CREATE INDEX idx_notifications_user_unread ON notifications(user_id, read_at) WHERE read_at IS NULL;
CREATE INDEX idx_notifications_team_type ON notifications(team_id, type, created_at DESC);

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
    ON team_file_permissions(team_id, file_id, subject_user_id)
    WHERE subject_type = 'USER';

CREATE UNIQUE INDEX uk_team_file_permissions_role
    ON team_file_permissions(team_id, file_id, subject_role)
    WHERE subject_type = 'ROLE';

CREATE INDEX idx_team_file_permissions_file ON team_file_permissions(team_id, file_id);

CREATE TRIGGER trg_team_file_permissions_updated_at
    BEFORE UPDATE ON team_file_permissions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_file_permissions IS '团队文件/文件夹细粒度权限，子项继承最近父级配置';
