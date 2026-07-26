ALTER TABLE team_event_outbox
    ADD COLUMN traceparent VARCHAR(55),
    ADD COLUMN tracestate VARCHAR(512);

ALTER TABLE team_tasks
    ADD COLUMN traceparent VARCHAR(55),
    ADD COLUMN tracestate VARCHAR(512);

COMMENT ON COLUMN team_event_outbox.traceparent IS 'W3C trace context captured when the durable event was created';
COMMENT ON COLUMN team_event_outbox.tracestate IS 'Optional W3C vendor trace state for the durable event';
COMMENT ON COLUMN team_tasks.traceparent IS 'W3C trace context linked by delayed task execution';
COMMENT ON COLUMN team_tasks.tracestate IS 'Optional W3C vendor trace state for delayed task execution';
