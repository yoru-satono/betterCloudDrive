ALTER TABLE users
    ADD COLUMN preferred_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN';

ALTER TABLE users
    ADD CONSTRAINT ck_users_preferred_locale
        CHECK (preferred_locale IN ('zh-CN', 'en-US'));

COMMENT ON COLUMN users.preferred_locale IS 'Preferred UI locale: zh-CN or en-US';
