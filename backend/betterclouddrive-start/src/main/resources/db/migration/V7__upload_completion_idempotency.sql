-- Store the file produced by a completed upload session so complete calls can be retried safely.
ALTER TABLE upload_sessions
    ADD COLUMN completed_file_id BIGINT;

ALTER TABLE upload_sessions
    ADD CONSTRAINT fk_upload_completed_file
        FOREIGN KEY (completed_file_id) REFERENCES files(id) ON DELETE SET NULL;

CREATE INDEX idx_upload_sessions_completed_file
    ON upload_sessions(completed_file_id)
    WHERE completed_file_id IS NOT NULL;

COMMENT ON COLUMN upload_sessions.completed_file_id IS 'File created by a completed upload session; used for idempotent complete retries';
