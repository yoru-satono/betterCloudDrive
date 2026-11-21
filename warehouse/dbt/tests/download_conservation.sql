SELECT * FROM {{ ref('ads_usage_daily') }}
WHERE generation_id = toUUID('{{ var("generation_id") }}')
    AND download_attempts != download_succeeded + download_failed + download_unknown
