-- Track OnlyOffice save callbacks so repeated callback delivery cannot create duplicate versions.
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
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_onlyoffice_callbacks_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_onlyoffice_callbacks_file FOREIGN KEY (file_id) REFERENCES files(id) ON DELETE CASCADE,
    CONSTRAINT fk_onlyoffice_callbacks_version FOREIGN KEY (version_id) REFERENCES file_versions(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX uk_onlyoffice_callback_save
    ON onlyoffice_callbacks(team_id, file_id, callback_key, status, content_url_hash);

CREATE INDEX idx_onlyoffice_callbacks_file
    ON onlyoffice_callbacks(file_id, created_at DESC);

COMMENT ON TABLE onlyoffice_callbacks IS 'OnlyOffice save callback idempotency records';
COMMENT ON COLUMN onlyoffice_callbacks.callback_key IS 'OnlyOffice document key from the callback payload';
COMMENT ON COLUMN onlyoffice_callbacks.content_url_hash IS 'SHA-256 of callback download URL; separates real later saves from duplicate delivery';
COMMENT ON COLUMN onlyoffice_callbacks.state IS '1=processing, 2=saved';
