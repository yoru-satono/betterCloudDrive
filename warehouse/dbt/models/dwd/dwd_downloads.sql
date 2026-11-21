WITH attempts AS (
SELECT attempt_id,
    minIf(occurred_at, event_type = 'DOWNLOAD_STARTED') AS started_at,
    argMaxIf(tuple(actor_user_id, team_id), occurred_at, event_type = 'DOWNLOAD_STARTED') AS actor_context,
    if(countIf(event_type = 'DOWNLOAD_FINISHED') = 0, 'UNKNOWN',
        argMaxIf(outcome, occurred_at, event_type = 'DOWNLOAD_FINISHED')) AS transfer_outcome,
    maxIf(byte_count, event_type = 'DOWNLOAD_FINISHED') AS written_bytes
FROM {{ ref('dwd_events') }}
WHERE event_type IN ('DOWNLOAD_STARTED', 'DOWNLOAD_FINISHED')
GROUP BY attempt_id
HAVING countIf(event_type = 'DOWNLOAD_STARTED') > 0
)
SELECT attempt_id, started_at, actor_context.1 AS actor_user_id,
    actor_context.2 AS team_id, transfer_outcome, written_bytes FROM attempts
