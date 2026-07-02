-- Remove data created by automated E2E and black-box tests.
--
-- User cleanup intentionally requires both a test-owned email domain and a
-- known automated-test username prefix. This keeps the script useful for local
-- databases without making broad assumptions about real users or teams.

BEGIN;

CREATE TEMP TABLE bcd_e2e_users ON COMMIT DROP AS
SELECT id
FROM users
WHERE (
        lower(email) LIKE '%@test.local'
        OR lower(email) LIKE '%@test.com'
    )
    AND (
        username LIKE 'e2e\_%' ESCAPE '\'
        OR username LIKE 'test\_%' ESCAPE '\'
        OR username LIKE 'admin\_%' ESCAPE '\'
        OR username LIKE 'reg\_%' ESCAPE '\'
        OR username LIKE 'team_user\_%' ESCAPE '\'
        OR username LIKE 'lifecycle_editor\_%' ESCAPE '\'
        OR username LIKE 'lifecycle_rejecter\_%' ESCAPE '\'
        OR username LIKE 'viewer\_%' ESCAPE '\'
        OR username LIKE 'outsider\_%' ESCAPE '\'
        OR username LIKE 'matrix_admin\_%' ESCAPE '\'
        OR username LIKE 'matrix_editor\_%' ESCAPE '\'
        OR username LIKE 'matrix_viewer\_%' ESCAPE '\'
        OR username LIKE 'ui%'
        OR username LIKE 'tui%'
    );

CREATE TEMP TABLE bcd_e2e_teams ON COMMIT DROP AS
SELECT id
FROM teams
WHERE owner_id IN (SELECT id FROM bcd_e2e_users);

CREATE TEMP TABLE bcd_e2e_files ON COMMIT DROP AS
SELECT id
FROM files
WHERE user_id IN (SELECT id FROM bcd_e2e_users)
    OR team_id IN (SELECT id FROM bcd_e2e_teams);

WITH
deleted_test_invites AS (
    DELETE FROM team_invites
    WHERE lower(email) LIKE '%@test.local'
        OR lower(email) LIKE '%@test.com'
    RETURNING 1
),
deleted_notifications AS (
    DELETE FROM notifications
    WHERE user_id IN (SELECT id FROM bcd_e2e_users)
        OR team_id IN (SELECT id FROM bcd_e2e_teams)
    RETURNING 1
),
deleted_team_audit AS (
    DELETE FROM team_operation_logs
    WHERE team_id IN (SELECT id FROM bcd_e2e_teams)
        OR actor_user_id IN (SELECT id FROM bcd_e2e_users)
    RETURNING 1
),
deleted_audit AS (
    DELETE FROM operation_logs
    WHERE user_id IN (SELECT id FROM bcd_e2e_users)
        OR (
            target_type IN ('USER', 'MEMBER')
            AND target_id IN (SELECT id FROM bcd_e2e_users)
        )
        OR (
            target_type IN ('FILE', 'FOLDER')
            AND target_id IN (SELECT id FROM bcd_e2e_files)
        )
        OR (
            target_type = 'TEAM'
            AND target_id IN (SELECT id FROM bcd_e2e_teams)
        )
    RETURNING 1
),
deleted_teams AS (
    DELETE FROM teams
    WHERE id IN (SELECT id FROM bcd_e2e_teams)
    RETURNING 1
),
deleted_users AS (
    DELETE FROM users
    WHERE id IN (SELECT id FROM bcd_e2e_users)
    RETURNING 1
)
SELECT
    (SELECT count(*) FROM bcd_e2e_users) AS matched_users,
    (SELECT count(*) FROM bcd_e2e_teams) AS matched_teams,
    (SELECT count(*) FROM bcd_e2e_files) AS matched_files,
    (SELECT count(*) FROM deleted_users) AS deleted_users,
    (SELECT count(*) FROM deleted_teams) AS deleted_teams,
    (SELECT count(*) FROM deleted_test_invites) AS deleted_test_invites,
    (SELECT count(*) FROM deleted_notifications) AS deleted_notifications,
    (SELECT count(*) FROM deleted_team_audit) AS deleted_team_audit,
    (SELECT count(*) FROM deleted_audit) AS deleted_audit;

COMMIT;
