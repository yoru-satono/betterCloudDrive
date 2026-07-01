-- ============================================================
-- Team file space MVP
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

    CONSTRAINT fk_teams_owner FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_teams_owner ON teams(owner_id);
CREATE INDEX idx_teams_deleted_at ON teams(deleted_at);

CREATE TRIGGER trg_teams_updated_at
    BEFORE UPDATE ON teams
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE teams IS '团队表';
COMMENT ON COLUMN teams.storage_quota IS '团队独立存储配额（字节）';
COMMENT ON COLUMN teams.storage_used IS '团队已用存储（字节）';

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

ALTER TABLE files
    ADD COLUMN team_id BIGINT;

ALTER TABLE files
    ADD CONSTRAINT fk_files_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

CREATE INDEX idx_files_team_parent ON files(team_id, parent_id) WHERE team_id IS NOT NULL AND is_deleted = FALSE;
CREATE INDEX idx_files_personal_user_parent ON files(user_id, parent_id) WHERE team_id IS NULL AND is_deleted = FALSE;
CREATE INDEX idx_files_team_deleted ON files(team_id, is_deleted) WHERE team_id IS NOT NULL;

COMMENT ON COLUMN files.team_id IS 'NULL=个人文件，非空=团队文件空间';

ALTER TABLE upload_sessions
    ADD COLUMN team_id BIGINT;

ALTER TABLE upload_sessions
    ADD CONSTRAINT fk_upload_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

CREATE INDEX idx_upload_sessions_team ON upload_sessions(team_id) WHERE team_id IS NOT NULL;

COMMENT ON COLUMN upload_sessions.team_id IS 'NULL=个人上传，非空=团队上传';
