ALTER TABLE share_links
    ADD COLUMN team_id BIGINT;

ALTER TABLE share_links
    ADD CONSTRAINT fk_share_links_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

CREATE INDEX idx_share_links_team ON share_links(team_id, is_canceled, created_at DESC)
    WHERE team_id IS NOT NULL;

CREATE INDEX idx_share_links_user_personal ON share_links(user_id, is_canceled, created_at DESC)
    WHERE team_id IS NULL;

COMMENT ON COLUMN share_links.team_id IS 'NULL=个人分享，非空=团队文件分享';
