ALTER TABLE operation_logs
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status_code INT,
    ADD COLUMN IF NOT EXISTS error_code INT;

CREATE INDEX IF NOT EXISTS idx_oplog_request_id ON operation_logs(request_id);
CREATE INDEX IF NOT EXISTS idx_oplog_trace_id ON operation_logs(trace_id);
CREATE INDEX IF NOT EXISTS idx_oplog_status_created ON operation_logs(status_code, created_at);

COMMENT ON COLUMN operation_logs.request_id IS '单次 HTTP 请求 ID，用于 API 响应、运行日志和审计日志关联';
COMMENT ON COLUMN operation_logs.trace_id IS '分布式追踪 ID，用于关联 OpenTelemetry/Tempo trace';
COMMENT ON COLUMN operation_logs.status_code IS 'HTTP 状态码';
COMMENT ON COLUMN operation_logs.error_code IS '业务错误码，暂无业务响应解析时可为空';
