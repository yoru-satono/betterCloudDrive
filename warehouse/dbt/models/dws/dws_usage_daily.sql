WITH facts AS (
    SELECT toDate(occurred_at, 'Asia/Shanghai') AS day, actor_user_id, team_id,
        event_type, mode, '' AS transfer_outcome, byte_count
    FROM {{ ref('dwd_events') }}
    WHERE event_type NOT IN ('DOWNLOAD_STARTED', 'DOWNLOAD_FINISHED')
    UNION ALL
    SELECT toDate(started_at, 'Asia/Shanghai'), actor_user_id, team_id,
        'DOWNLOAD', '', transfer_outcome, written_bytes
    FROM {{ ref('dwd_downloads') }}
), expanded AS (
    SELECT *, arrayJoin(arrayConcat(
        [tuple('ALL', toInt64(0)), tuple(if(isNull(team_id), 'PERSONAL', 'TEAM'), toInt64(0))],
        if(isNull(team_id), [], [tuple('TEAM', assumeNotNull(team_id))])
    )) AS scope
    FROM facts
)
SELECT day, scope.1 AS scope_type, scope.2 AS scope_id,
    countIf(event_type = 'USER_REGISTERED') AS registered_users,
    uniqExactIf(actor_user_id, isNotNull(actor_user_id) AND event_type != 'USER_REGISTERED') AS active_users,
    uniqExactIf(team_id, isNotNull(team_id) AND isNotNull(actor_user_id)) AS active_teams,
    countIf(event_type = 'USER_ACTIVITY') AS operations,
    countIf(event_type = 'UPLOAD_COMMITTED') AS uploads,
    sumIf(byte_count, event_type = 'UPLOAD_COMMITTED') AS uploaded_logical_bytes,
    countIf(event_type = 'UPLOAD_COMMITTED' AND mode = 'INSTANT') AS instant_uploads,
    countIf(event_type = 'DOWNLOAD') AS download_attempts,
    countIf(event_type = 'DOWNLOAD' AND transfer_outcome = 'SUCCEEDED') AS download_succeeded,
    countIf(event_type = 'DOWNLOAD' AND transfer_outcome = 'FAILED') AS download_failed,
    countIf(event_type = 'DOWNLOAD' AND transfer_outcome = 'UNKNOWN') AS download_unknown,
    sumIf(byte_count, event_type = 'DOWNLOAD') AS download_written_bytes,
    countIf(event_type = 'SHARE_CREATED') AS shares_created,
    countIf(event_type = 'SHARE_CANCELED') AS shares_canceled,
    countIf(event_type = 'SHARE_ACCESSED') AS share_accesses
FROM expanded GROUP BY day, scope_type, scope_id
