-- V4: Widen SMALLINT columns to INTEGER for JPA entity compatibility
ALTER TABLE users ALTER COLUMN status TYPE INTEGER;
ALTER TABLE upload_sessions ALTER COLUMN status TYPE INTEGER;
ALTER TABLE operation_logs ALTER COLUMN result TYPE INTEGER;
