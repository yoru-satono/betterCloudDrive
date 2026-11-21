WITH anchors AS (
    SELECT business_date AS day,
        argMin(tuple(batch_id, captured_at), tuple(captured_at, batch_id)) AS snapshot
    FROM {{ source('raw', 'ods_snapshot_batches') }} GROUP BY business_date
), records AS (
    SELECT * FROM {{ ref('dwd_snapshots') }}
    UNION ALL
    SELECT day, snapshot.1, snapshot.2, 'baseline', toInt64(0), CAST(NULL, 'Nullable(Int64)'),
        if(anchor = 1, toNullable(toInt64(0)), CAST(NULL, 'Nullable(Int64)')),
        CAST(NULL, 'Nullable(Int64)'), '', toInt16(0), toUInt8(0), toUInt8(0), toUInt64(0), toUInt64(0),
        CAST(NULL AS Nullable(DateTime64(6, 'UTC'))), CAST(NULL AS Nullable(DateTime64(6, 'UTC')))
    FROM anchors ARRAY JOIN [0, 1] AS anchor
), expanded AS (
    SELECT *, arrayJoin(arrayConcat(
        [tuple('ALL', toInt64(0)), tuple(if(isNull(team_id), 'PERSONAL', 'TEAM'), toInt64(0))],
        if(isNull(team_id), [], [tuple('TEAM', assumeNotNull(team_id))])
    )) AS scope
    FROM records
)
SELECT day, scope.1 AS scope_type, scope.2 AS scope_id, max(captured_at) AS sampled_at,
    countIf(record_type = 'user' AND is_deleted = 0) AS total_users,
    countIf(record_type = 'user' AND is_deleted = 0 AND status = 1) AS enabled_users,
    countIf(record_type = 'team' AND is_deleted = 0 AND archived = 0) AS total_teams,
    countIf(record_type = 'member' AND status = 1) AS member_count,
    countIf(record_type = 'file' AND kind = 'file' AND is_deleted = 0) AS file_count,
    countIf(record_type = 'file' AND kind = 'folder' AND is_deleted = 0) AS folder_count,
    countIf(record_type = 'file' AND kind = 'file' AND is_deleted = 1) AS trash_file_count,
    sumIf(size_bytes, record_type = 'file' AND kind = 'file' AND is_deleted = 0) AS current_bytes,
    sumIf(size_bytes, record_type = 'file' AND kind = 'file' AND is_deleted = 1) AS trash_bytes,
    sumIf(size_bytes, record_type = 'version') AS version_bytes,
    sumIf(quota_bytes, record_type IN ('user', 'team') AND is_deleted = 0) AS quota_bytes,
    countIf(record_type = 'share' AND is_deleted = 0 AND (isNull(expires_at) OR expires_at > captured_at)) AS active_shares
FROM expanded GROUP BY day, scope_type, scope_id
