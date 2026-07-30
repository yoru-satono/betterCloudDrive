ALTER TABLE users
    DROP CONSTRAINT ck_users_preferred_locale;

ALTER TABLE users
    ALTER COLUMN preferred_locale TYPE VARCHAR(35);

COMMENT ON COLUMN users.preferred_locale IS
    'Preferred UI locale as a canonical BCP 47 language tag';
