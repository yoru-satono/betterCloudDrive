ALTER TABLE users ADD COLUMN quota_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE teams ADD COLUMN quota_revision BIGINT NOT NULL DEFAULT 0;

-- Existing installations require the explicit, resumable offline Redis cutover.
CREATE TABLE quota_accounting_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    ready BOOLEAN NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO quota_accounting_state (id, ready)
SELECT 1, NOT EXISTS (SELECT 1 FROM users) AND NOT EXISTS (SELECT 1 FROM teams);

CREATE TABLE quota_legacy_transfers (
    scope VARCHAR(8) NOT NULL CHECK (scope IN ('PERSONAL', 'TEAM')),
    owner_id BIGINT NOT NULL,
    delta BIGINT NOT NULL,
    used_before BIGINT,
    used_after BIGINT,
    status VARCHAR(24) NOT NULL CHECK (status IN ('APPLIED', 'OWNER_ABSENT', 'ACKNOWLEDGED')),
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    PRIMARY KEY (scope, owner_id)
);
