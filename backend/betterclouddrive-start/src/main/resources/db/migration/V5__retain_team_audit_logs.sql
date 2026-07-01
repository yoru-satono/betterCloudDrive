ALTER TABLE team_operation_logs
    DROP CONSTRAINT IF EXISTS fk_team_operation_logs_team;

COMMENT ON COLUMN team_operation_logs.team_id IS '团队 ID 快照；团队硬删除后仍保留审计日志用于追溯';
