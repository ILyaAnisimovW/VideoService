CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_canonical CHECK (email = lower(btrim(email))),
    CONSTRAINT users_role_check CHECK (role IN ('USER', 'MODERATOR', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'BLOCKED'))
);
