-- Team event outbox for future asynchronous workers.
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

CREATE INDEX idx_team_event_outbox_pending
    ON team_event_outbox(status, available_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_team_event_outbox_aggregate
    ON team_event_outbox(aggregate_type, aggregate_id, created_at DESC);

CREATE INDEX idx_team_event_outbox_event_type
    ON team_event_outbox(event_type, created_at DESC);

CREATE TRIGGER trg_team_event_outbox_updated_at
    BEFORE UPDATE ON team_event_outbox
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE team_event_outbox IS 'Team-domain event outbox for future queue consumers';
COMMENT ON COLUMN team_event_outbox.aggregate_type IS 'TEAM, NOTIFICATION, or future aggregate category';
COMMENT ON COLUMN team_event_outbox.status IS 'PENDING, PROCESSING, PROCESSED, FAILED';
