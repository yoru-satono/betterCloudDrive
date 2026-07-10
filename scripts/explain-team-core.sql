\set ON_ERROR_STOP on

BEGIN;

CREATE TEMP TABLE perf_files (
    id BIGINT PRIMARY KEY,
    team_id BIGINT,
    parent_id BIGINT,
    file_type VARCHAR(16) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    is_deleted BOOLEAN NOT NULL
);
CREATE INDEX perf_files_team_parent_listing
    ON perf_files(team_id, parent_id, file_type, file_name)
    WHERE team_id IS NOT NULL AND is_deleted = FALSE;
INSERT INTO perf_files
SELECT value,
       value % 10 + 1,
       value % 100,
       CASE WHEN value % 5 = 0 THEN 'folder' ELSE 'file' END,
       'entry-' || lpad(value::text, 8, '0'),
       value % 20 = 0
FROM generate_series(1, 100000) AS value;

CREATE TEMP TABLE perf_team_operation_logs (
    id BIGINT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    actor_user_id BIGINT,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64),
    result INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX perf_audit_team_created
    ON perf_team_operation_logs(team_id, created_at DESC);
CREATE INDEX perf_audit_team_actor_created
    ON perf_team_operation_logs(team_id, actor_user_id, created_at DESC);
CREATE INDEX perf_audit_team_action_created
    ON perf_team_operation_logs(team_id, action, created_at DESC);
CREATE INDEX perf_audit_team_target_type_created
    ON perf_team_operation_logs(team_id, target_type, created_at DESC);
CREATE INDEX perf_audit_team_result_created
    ON perf_team_operation_logs(team_id, result, created_at DESC);
INSERT INTO perf_team_operation_logs
SELECT value,
       value % 10 + 1,
       value % 100 + 1,
       (ARRAY['FILE_CREATE', 'FILE_READ', 'FILE_DELETE', 'MEMBER_UPDATE'])[value % 4 + 1],
       (ARRAY['FILE', 'MEMBER'])[value % 2 + 1],
       CASE WHEN value % 20 = 0 THEN 0 ELSE 1 END,
       CURRENT_TIMESTAMP - value * INTERVAL '1 second'
FROM generate_series(1, 200000) AS value;

CREATE TEMP TABLE perf_notifications (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX perf_notifications_user_created
    ON perf_notifications(user_id, created_at DESC);
CREATE INDEX perf_notifications_user_type_created
    ON perf_notifications(user_id, type, created_at DESC);
CREATE INDEX perf_notifications_user_unread_created
    ON perf_notifications(user_id, created_at DESC)
    WHERE read_at IS NULL;
INSERT INTO perf_notifications
SELECT value,
       value % 100 + 1,
       (ARRAY['TEAM_INVITE', 'TEAM_QUOTA_ALERT', 'TEAM_PERMISSION_UPDATE'])[value % 3 + 1],
       CASE WHEN value % 3 = 0 THEN NULL ELSE CURRENT_TIMESTAMP END,
       CURRENT_TIMESTAMP - value * INTERVAL '1 second'
FROM generate_series(1, 100000) AS value;

CREATE TEMP TABLE perf_team_file_permissions (
    id BIGINT PRIMARY KEY,
    team_id BIGINT NOT NULL,
    file_id BIGINT NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_user_id BIGINT,
    subject_role VARCHAR(24)
);
CREATE INDEX perf_permissions_file
    ON perf_team_file_permissions(team_id, file_id);
CREATE UNIQUE INDEX perf_permissions_user
    ON perf_team_file_permissions(team_id, file_id, subject_user_id)
    WHERE subject_type = 'USER';
CREATE UNIQUE INDEX perf_permissions_role
    ON perf_team_file_permissions(team_id, file_id, subject_role)
    WHERE subject_type = 'ROLE';
INSERT INTO perf_team_file_permissions
SELECT value,
       value % 10 + 1,
       value,
       CASE WHEN value % 2 = 0 THEN 'USER' ELSE 'ROLE' END,
       CASE WHEN value % 2 = 0 THEN value % 1000 + 1 ELSE NULL END,
       CASE WHEN value % 2 = 1 THEN (ARRAY['ADMIN', 'EDITOR', 'VIEWER'])[value % 3 + 1] ELSE NULL END
FROM generate_series(1, 100000) AS value;

ANALYZE perf_files;
ANALYZE perf_team_operation_logs;
ANALYZE perf_notifications;
ANALYZE perf_team_file_permissions;

\echo 'Team folder listing: expect perf_files_team_parent_listing'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, file_type, file_name
FROM perf_files
WHERE team_id = 3 AND parent_id = 42 AND is_deleted = FALSE
ORDER BY file_type ASC, file_name ASC;

\echo 'Audit actor filter: expect perf_audit_team_actor_created'
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM perf_team_operation_logs
WHERE team_id = 3 AND actor_user_id = 3
ORDER BY created_at DESC
LIMIT 50;

\echo 'Audit action filter: expect perf_audit_team_action_created'
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM perf_team_operation_logs
WHERE team_id = 3 AND action = 'FILE_DELETE'
ORDER BY created_at DESC
LIMIT 50;

\echo 'Unread notifications: expect perf_notifications_user_unread_created'
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM perf_notifications
WHERE user_id = 4 AND read_at IS NULL
ORDER BY created_at DESC
LIMIT 20;

\echo 'Notification type filter: expect perf_notifications_user_type_created'
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM perf_notifications
WHERE user_id = 4 AND type = 'TEAM_INVITE'
ORDER BY created_at DESC
LIMIT 20;

\echo 'Exact user permission: expect perf_permissions_user'
EXPLAIN (ANALYZE, BUFFERS)
SELECT *
FROM perf_team_file_permissions
WHERE team_id = 3 AND file_id = 42 AND subject_type = 'USER' AND subject_user_id = 43;

ROLLBACK;
