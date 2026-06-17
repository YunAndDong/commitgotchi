CREATE TABLE game_states (
    user_id    BIGINT PRIMARY KEY,
    state_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_game_states_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_game_states_state_json
        CHECK (length(btrim(state_json)) > 0)
);
