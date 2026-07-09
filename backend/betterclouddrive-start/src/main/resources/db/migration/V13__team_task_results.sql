ALTER TABLE team_tasks
    ADD COLUMN payload TEXT,
    ADD COLUMN result_path VARCHAR(1024),
    ADD COLUMN result_file_name VARCHAR(255),
    ADD COLUMN result_content_type VARCHAR(128),
    ADD COLUMN result_size_bytes BIGINT,
    ADD COLUMN result_expires_at TIMESTAMP;

CREATE INDEX idx_team_tasks_pending_type_created ON team_tasks(task_type, status, created_at ASC);
CREATE INDEX idx_team_tasks_result_expires ON team_tasks(result_expires_at)
    WHERE result_expires_at IS NOT NULL;
