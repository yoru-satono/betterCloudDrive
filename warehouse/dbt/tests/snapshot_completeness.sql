SELECT b.batch_id
FROM {{ source('raw', 'ods_snapshot_batches') }} b
LEFT JOIN (
    SELECT batch_id, count() AS actual FROM {{ source('raw', 'ods_snapshots') }} GROUP BY batch_id
) s ON b.batch_id = s.batch_id
WHERE b.captured_at >= now() - INTERVAL 90 DAY AND b.record_count != s.actual
