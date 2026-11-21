-- Explicit query-time deduplication: asynchronous MergeTree merges are irrelevant.
WITH canonical AS (
    SELECT event_id,
        argMax(tuple(event_type, occurred_at, actor_user_id, team_id, resource_id,
            attempt_id, channel, mode, outcome, byte_count, schema_version), ingested_at) AS fact
    FROM {{ source('raw', 'ods_events') }}
    GROUP BY event_id
)
SELECT event_id, fact.1 AS event_type, fact.2 AS occurred_at,
    fact.3 AS actor_user_id, fact.4 AS team_id, fact.5 AS resource_id,
    fact.6 AS attempt_id, fact.7 AS channel, fact.8 AS mode,
    fact.9 AS outcome, fact.10 AS byte_count, fact.11 AS schema_version
FROM canonical
