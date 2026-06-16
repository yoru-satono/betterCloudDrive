-- ============================================================
-- Development seed users
-- DML-only repeatable migration. Existing users are preserved.
-- ============================================================

INSERT INTO users (
    username,
    password_hash,
    email,
    email_verified,
    role,
    status,
    storage_quota,
    storage_used,
    created_at,
    updated_at
) VALUES
    (
        'chain',
        '$2a$12$cvcJD6S/WTwQDh9oszFr1.miXxgJ2ezA3hA.Ffr3irwFHyhFD2/G2',
        'chain@test.local',
        TRUE,
        'ROLE_USER',
        1,
        10737418240,
        0,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    ),
    (
        'admin',
        '$2a$12$TKVybYLb5sxGZIe/bV.g6.ZYADC/4a1hqtIm3V6h3hVrtOSf4DBRi',
        'admin@test.local',
        TRUE,
        'ROLE_ADMIN',
        1,
        10737418240,
        0,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    )
ON CONFLICT DO NOTHING;
