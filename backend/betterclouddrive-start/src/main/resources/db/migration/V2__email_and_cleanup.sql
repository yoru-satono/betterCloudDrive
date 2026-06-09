-- V2: Remove unused phone column, add email verification tracking
ALTER TABLE users DROP COLUMN IF EXISTS phone;
ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN users.email_verified IS 'Whether the user has verified their email address';
