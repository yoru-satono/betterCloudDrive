CREATE TABLE IF NOT EXISTS ods_events (
    event_id UUID,
    event_type LowCardinality(String),
    occurred_at DateTime64(6, 'UTC'),
    actor_user_id Nullable(Int64),
    team_id Nullable(Int64),
    resource_id Nullable(Int64),
    attempt_id Nullable(UUID),
    channel LowCardinality(String),
    mode LowCardinality(String),
    outcome LowCardinality(String),
    byte_count UInt64,
    schema_version UInt16,
    ingested_at DateTime64(6, 'UTC') DEFAULT now64(6)
) ENGINE = MergeTree ORDER BY (event_id, ingested_at)
TTL occurred_at + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS ods_snapshots (
    batch_id UUID,
    captured_at DateTime64(6, 'UTC'),
    record_type LowCardinality(String),
    id Int64,
    user_id Nullable(Int64),
    team_id Nullable(Int64),
    parent_id Nullable(Int64),
    kind LowCardinality(String),
    status Int16,
    is_deleted UInt8,
    archived UInt8,
    size_bytes UInt64,
    quota_bytes UInt64,
    created_at Nullable(DateTime64(6, 'UTC')),
    expires_at Nullable(DateTime64(6, 'UTC'))
) ENGINE = MergeTree ORDER BY (batch_id, record_type, id)
TTL captured_at + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS ods_snapshot_batches (
    batch_id UUID,
    business_date Date,
    captured_at DateTime64(6, 'UTC'),
    record_count UInt64
) ENGINE = MergeTree ORDER BY (business_date, captured_at, batch_id)
TTL captured_at + INTERVAL 90 DAY;

CREATE TABLE IF NOT EXISTS pipeline_runs (
    run_id UUID,
    job LowCardinality(String),
    status LowCardinality(String),
    started_at DateTime64(6, 'UTC'),
    updated_at DateTime64(6, 'UTC'),
    error_key LowCardinality(String)
) ENGINE = MergeTree ORDER BY (run_id, updated_at)
TTL updated_at + INTERVAL 365 DAY;

CREATE TABLE IF NOT EXISTS publications (
    generation_id UUID,
    published_at DateTime64(6, 'UTC'),
    data_through DateTime64(6, 'UTC')
) ENGINE = MergeTree ORDER BY published_at
TTL published_at + INTERVAL 365 DAY;
