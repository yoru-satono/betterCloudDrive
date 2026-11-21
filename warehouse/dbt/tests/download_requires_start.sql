-- Raw TTL is timestamp-based, while ADS rebuilds complete business dates.
-- The oldest partial raw date may retain an END after its START has expired;
-- it is outside the rebuild window and must not block a new publication.
SELECT attempt_id FROM {{ ref('dwd_events') }}
WHERE event_type IN ('DOWNLOAD_STARTED', 'DOWNLOAD_FINISHED')
GROUP BY attempt_id
HAVING countIf(event_type = 'DOWNLOAD_FINISHED'
    AND occurred_at >= toDate(now(), 'Asia/Shanghai') - 89) > 0
    AND countIf(event_type = 'DOWNLOAD_STARTED') = 0
