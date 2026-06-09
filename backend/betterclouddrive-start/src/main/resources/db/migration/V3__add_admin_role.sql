-- V3: Add role column for admin access control
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(32) NOT NULL DEFAULT 'ROLE_USER';

COMMENT ON COLUMN users.role IS 'User role: ROLE_USER (default) or ROLE_ADMIN';
