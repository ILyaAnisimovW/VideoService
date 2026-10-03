-- V5__add_email_verification.sql

-- Флаг верификации на таблице users
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Таблица кодов верификации
CREATE TABLE verification_codes
(
    id         UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id    UUID      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code       VARCHAR(6) NOT NULL,          -- 6-значный цифровой код
    used       BOOLEAN   NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT verification_codes_user_unique UNIQUE (user_id)  -- один активный код на юзера
);

CREATE INDEX idx_verification_codes_user_id ON verification_codes (user_id);
