CREATE TABLE refresh_tokens
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(255) NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP    NOT NULL,

    CONSTRAINT refresh_tokens_hash_unique UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_user_id    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
