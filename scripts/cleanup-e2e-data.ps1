[CmdletBinding()]
param(
    [switch]$DryRun,
    [string]$ComposeFile = "docker-compose.yml",
    [string]$PostgresService = "postgres"
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path (Join-Path $ScriptRoot "..")
$SqlFile = Join-Path $ScriptRoot "e2e-cleanup.sql"

function Read-EnvFile {
    param([string]$Path)
    $values = @{}
    if (-not (Test-Path $Path)) {
        return $values
    }
    foreach ($line in Get-Content -Path $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed.Split("=", 2)
        if ($parts.Length -eq 2) {
            $values[$parts[0].Trim()] = $parts[1].Trim().Trim('"').Trim("'")
        }
    }
    return $values
}

function Invoke-Psql {
    param(
        [string]$Sql,
        [string]$User,
        [string]$Database
    )
    $Sql | docker compose -f $ComposeFile exec -T $PostgresService psql -U $User -d $Database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed with exit code $LASTEXITCODE"
    }
}

Set-Location $RepoRoot

$databaseEnv = Read-EnvFile "config/env-files/database.env"
if ($databaseEnv.Count -eq 0) {
    $databaseEnv = Read-EnvFile "config/env-files/database.env.example"
}

$pgUser = if ($databaseEnv["POSTGRES_USER"]) { $databaseEnv["POSTGRES_USER"] } else { "bcd_user" }
$pgDatabase = if ($databaseEnv["POSTGRES_DB"]) { $databaseEnv["POSTGRES_DB"] } else { "better_cloud_drive" }

$dryRunSql = @"
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
SELECT
    (SELECT count(*) FROM bcd_e2e_users) AS matched_users,
    (SELECT count(*) FROM bcd_e2e_teams) AS matched_teams,
    (SELECT count(*) FROM bcd_e2e_files) AS matched_files,
    (SELECT count(*) FROM team_invites WHERE lower(email) LIKE '%@test.local' OR lower(email) LIKE '%@test.com') AS matched_test_invites,
    (SELECT count(*) FROM notifications WHERE user_id IN (SELECT id FROM bcd_e2e_users) OR team_id IN (SELECT id FROM bcd_e2e_teams)) AS matched_notifications,
    (SELECT count(*) FROM team_operation_logs WHERE team_id IN (SELECT id FROM bcd_e2e_teams) OR actor_user_id IN (SELECT id FROM bcd_e2e_users)) AS matched_team_audit,
    (SELECT count(*) FROM operation_logs WHERE user_id IN (SELECT id FROM bcd_e2e_users)) AS matched_user_audit;
ROLLBACK;
"@

if ($DryRun) {
    Invoke-Psql -Sql $dryRunSql -User $pgUser -Database $pgDatabase
} else {
    if (-not (Test-Path $SqlFile)) {
        throw "Cleanup SQL file not found: $SqlFile"
    }
    Invoke-Psql -Sql (Get-Content -Path $SqlFile -Raw) -User $pgUser -Database $pgDatabase
}
