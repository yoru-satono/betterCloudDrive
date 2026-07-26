CREATE TABLE dead_letter_records (
    id                UUID PRIMARY KEY,
    original_message_id VARCHAR(128) NOT NULL,
    original_queue    VARCHAR(32) NOT NULL,
    routing_key       VARCHAR(64) NOT NULL,
    task_type         VARCHAR(64) NOT NULL,
    failure_category  VARCHAR(32) NOT NULL,
    delivery_count    INTEGER NOT NULL DEFAULT 1,
    first_failed_at   TIMESTAMP NOT NULL,
    last_failed_at    TIMESTAMP NOT NULL,
    request_id        VARCHAR(128),
    trace_id          VARCHAR(32),
    traceparent       VARCHAR(55),
    tracestate        VARCHAR(512),
    test_marker       VARCHAR(128),
    payload           TEXT NOT NULL,
    status            VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    replay_token      UUID,
    resolution_reason VARCHAR(500),
    resolved_by       BIGINT,
    resolved_at       TIMESTAMP,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_dead_letter_original_message UNIQUE (original_message_id),
    CONSTRAINT ck_dead_letter_queue CHECK (original_queue IN ('notifications', 'team-tasks', 'unknown')),
    CONSTRAINT ck_dead_letter_category CHECK (failure_category IN ('invalid-payload', 'missing-record', 'delivery-exhausted', 'unknown')),
    CONSTRAINT ck_dead_letter_status CHECK (status IN ('OPEN', 'REPLAYING', 'REPLAYED', 'DISCARDED'))
);

CREATE INDEX idx_dead_letter_open_failed
    ON dead_letter_records(status, last_failed_at DESC) WHERE status = 'OPEN';
CREATE INDEX idx_dead_letter_source
    ON dead_letter_records(original_queue, failure_category, status);

CREATE TRIGGER trg_dead_letter_records_updated_at
    BEFORE UPDATE ON dead_letter_records
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE dead_letter_records IS 'Queryable, auditable dead-letter control records; payload is never exposed by APIs';
COMMENT ON COLUMN dead_letter_records.replay_token IS 'Idempotency token for a single accepted replay transition';
COMMENT ON COLUMN dead_letter_records.test_marker IS 'Explicit bcd-e2e marker used by scoped automated cleanup only';
