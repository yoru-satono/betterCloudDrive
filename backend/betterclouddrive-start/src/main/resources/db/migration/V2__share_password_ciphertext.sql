DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'share_links'
          AND column_name = 'password_hash'
    ) THEN
        ALTER TABLE share_links RENAME COLUMN password_hash TO password_ciphertext;
    END IF;
END $$;

UPDATE share_links
SET password_ciphertext = NULL
WHERE password_ciphertext IS NOT NULL;

COMMENT ON COLUMN share_links.password_ciphertext IS '分享密码密文，使用 AES-GCM 加密；NULL 表示无密码';
