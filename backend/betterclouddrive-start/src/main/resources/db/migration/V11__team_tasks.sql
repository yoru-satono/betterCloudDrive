CREATE TABLE team_tasks (
    id              BIGSERIAL PRIMARY KEY,
    team_id         BIGINT,
    actor_user_id   BIGINT,
    task_type       VARCHAR(64)  NOT NULL,
    status          VARCHAR(24)  NOT NULL,
    target_type     VARCHAR(32),
    target_id       BIGINT,
    title           VARCHAR(200) NOT NULL,
    progress        INTEGER      NOT NULL DEFAULT 0,
    result_message  TEXT,
    error_message   TEXT,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,

    CONSTRAINT fk_team_tasks_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE SET NULL,
    CONSTRAINT fk_team_tasks_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);

CREATE INDEX idx_team_tasks_team_created ON team_tasks(team_id, created_at DESC);
CREATE INDEX idx_team_tasks_team_status ON team_tasks(team_id, status, created_at DESC);
CREATE INDEX idx_team_tasks_type_status ON team_tasks(task_type, status, created_at DESC);

CREATE TRIGGER trg_team_tasks_updated_at
    BEFORE UPDATE ON team_tasks
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_tasks IS '团队长任务状态中心';
