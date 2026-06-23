ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL;

ALTER TABLE characters
    ADD COLUMN deleted_at TIMESTAMPTZ NULL;

DROP INDEX uq_one_active_character_per_user;

CREATE UNIQUE INDEX uq_one_active_character_per_user
    ON characters (user_id)
    WHERE is_active = true AND deleted_at IS NULL;

DROP INDEX idx_characters_user_created_at;
DROP INDEX idx_characters_user_active;

CREATE INDEX idx_characters_user_created_at
    ON characters (user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_characters_user_active
    ON characters (user_id, is_active)
    WHERE deleted_at IS NULL;
