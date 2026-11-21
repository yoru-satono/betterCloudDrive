WITH complete AS (
    SELECT business_date, argMin(batch_id, tuple(captured_at, batch_id)) AS batch_id
    FROM {{ source('raw', 'ods_snapshot_batches') }}
    GROUP BY business_date
)
SELECT c.business_date AS day, s.*
FROM {{ source('raw', 'ods_snapshots') }} s
INNER JOIN complete c ON c.batch_id = s.batch_id
