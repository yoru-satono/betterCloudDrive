-- Team file listings are ordered by type and name after filtering by team/folder.
DROP INDEX IF EXISTS idx_files_team_parent;
CREATE INDEX idx_files_team_parent_listing
    ON files(team_id, parent_id, file_type, file_name)
    WHERE team_id IS NOT NULL AND is_deleted = FALSE;

-- Optional audit filters are combined with team scope and descending pagination.
CREATE INDEX idx_team_operation_logs_team_actor_created
    ON team_operation_logs(team_id, actor_user_id, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_action_created
    ON team_operation_logs(team_id, action, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_target_type_created
    ON team_operation_logs(team_id, target_type, created_at DESC);
CREATE INDEX idx_team_operation_logs_team_result_created
    ON team_operation_logs(team_id, result, created_at DESC);

-- Notification pages are scoped by user and optionally by type/unread state.
CREATE INDEX idx_notifications_user_type_created
    ON notifications(user_id, type, created_at DESC);
DROP INDEX IF EXISTS idx_notifications_user_unread;
CREATE INDEX idx_notifications_user_unread_created
    ON notifications(user_id, created_at DESC)
    WHERE read_at IS NULL;
