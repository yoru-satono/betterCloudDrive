-- ============================================================
-- Team invites, audit logs, and team-scoped tags
-- ============================================================

CREATE TABLE team_invites (
    id                  BIGSERIAL PRIMARY KEY,
    team_id             BIGINT      NOT NULL,
    email               VARCHAR(255) NOT NULL,
    role                VARCHAR(24) NOT NULL,
    token               VARCHAR(64) NOT NULL,
    status              VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    invited_by_user_id  BIGINT      NOT NULL,
    accepted_by_user_id BIGINT,
    expires_at          TIMESTAMP   NOT NULL,
    accepted_at         TIMESTAMP,
    rejected_at         TIMESTAMP,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

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
    team_id       BIGINT      NOT NULL,
    actor_user_id BIGINT,
    action        VARCHAR(64) NOT NULL,
    target_type   VARCHAR(64),
    target_id     BIGINT,
    result        INTEGER     NOT NULL DEFAULT 1,
    status_code   INTEGER,
    request_id    VARCHAR(128),
    trace_id      VARCHAR(128),
    details       TEXT,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_team_operation_logs_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_team_operation_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_team_operation_logs_team_created ON team_operation_logs(team_id, created_at DESC);
CREATE INDEX idx_team_operation_logs_actor ON team_operation_logs(actor_user_id);
CREATE INDEX idx_team_operation_logs_action ON team_operation_logs(action);
CREATE INDEX idx_team_operation_logs_target ON team_operation_logs(target_type, target_id);

COMMENT ON TABLE team_operation_logs IS '团队审计日志';

ALTER TABLE tags
    ADD COLUMN team_id BIGINT;

ALTER TABLE tags
    ADD CONSTRAINT fk_tags_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS uk_tags_user_tag;

CREATE UNIQUE INDEX uk_tags_personal_user_tag ON tags(user_id, tag_name) WHERE team_id IS NULL;
CREATE UNIQUE INDEX uk_tags_team_tag ON tags(team_id, tag_name) WHERE team_id IS NOT NULL;
CREATE INDEX idx_tags_team ON tags(team_id, tag_name) WHERE team_id IS NOT NULL;

COMMENT ON COLUMN tags.team_id IS 'NULL=个人标签，非空=团队共享标签';

ALTER TABLE file_tags
    ADD COLUMN team_id BIGINT;

ALTER TABLE file_tags
    ADD CONSTRAINT fk_file_tags_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

CREATE INDEX idx_file_tags_team_tag ON file_tags(team_id, tag_id) WHERE team_id IS NOT NULL;

COMMENT ON COLUMN file_tags.team_id IS 'NULL=个人文件标签，非空=团队文件标签';
