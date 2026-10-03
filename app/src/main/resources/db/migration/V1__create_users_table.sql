CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE users
(
    id         UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name  VARCHAR(100) NOT NULL,
    phone      VARCHAR(20),
    role       VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status     VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_role_check   CHECK (role   IN ('USER', 'PROVIDER', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

CREATE INDEX idx_users_email ON users (email);

CREATE INDEX idx_users_first_name_trgm ON users USING gin (first_name gin_trgm_ops);
CREATE INDEX idx_users_last_name_trgm  ON users USING gin (last_name  gin_trgm_ops);
