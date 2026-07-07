ALTER TABLE onlyoffice_callbacks
    ADD COLUMN actor_user_id BIGINT,
    ADD COLUMN download_url TEXT,
    ADD COLUMN task_id BIGINT,
    ADD COLUMN last_attempt_at TIMESTAMP;

ALTER TABLE onlyoffice_callbacks
    ADD CONSTRAINT fk_onlyoffice_callbacks_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_onlyoffice_callbacks_task FOREIGN KEY (task_id) REFERENCES team_tasks(id) ON DELETE SET NULL;

CREATE INDEX idx_onlyoffice_callbacks_state_created
    ON onlyoffice_callbacks(state, created_at ASC);

CREATE INDEX idx_onlyoffice_callbacks_task
    ON onlyoffice_callbacks(task_id);

COMMENT ON COLUMN onlyoffice_callbacks.actor_user_id IS 'User that opened the editable OnlyOffice session';
COMMENT ON COLUMN onlyoffice_callbacks.download_url IS 'Resolved document server download URL for asynchronous save processing';
COMMENT ON COLUMN onlyoffice_callbacks.task_id IS 'Linked team task that exposes save progress and retry state';
COMMENT ON COLUMN onlyoffice_callbacks.last_attempt_at IS 'Last background save attempt timestamp';
COMMENT ON COLUMN onlyoffice_callbacks.state IS '1=pending, 2=saved, 3=failed';
