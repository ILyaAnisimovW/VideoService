-- ─────────────────────────────────────────────────────────────────
--  Seed: тестовые пользователи
--  Пароль у всех: Test1234!
--  Запуск: psql -U parking -d parking -f seed_users.sql
--          или через Flyway: положи в db/migration как R__seed_users.sql
-- ─────────────────────────────────────────────────────────────────

INSERT INTO users (id, email, password, first_name, last_name, phone, role, created_at, updated_at)
VALUES
    (
        gen_random_uuid(),
        'user@test.com',
        '$2b$10$YFTDqsTMqAj8fBUtim/HjuqcXBy.INUKAJ0e4..eSJnS9SMlnsOY.',
        'Иван', 'Петров', '+79991110001',
        'USER',
        now(), now()
    ),
    (
        gen_random_uuid(),
        'user2@test.com',
        '$2b$10$WQ9SW4ZULVgGG.X0ALCON.D6OAQI3.9JNzZPk8ZqRvWU3sfmuJK9y',
        'Мария', 'Сидорова', '+79991110002',
        'USER',
        now(), now()
    ),
    (
        gen_random_uuid(),
        'provider@test.com',
        '$2b$10$rbX50TxD6Uswt7r4pH.dyOHjEqKsrWvoe2U9/rzGU.gYAyj7epjZa',
        'Алексей', 'Провайдеров', '+79991110003',
        'PROVIDER',
        now(), now()
    ),
    (
        gen_random_uuid(),
        'admin@test.com',
        '$2b$10$cYEhNnI9z9O8TgCeWs2/Xu4SaClatf28qtGEDNB1t.2r.H0UUL0Ti',
        'Админ', 'Системный', '+79991110004',
        'ADMIN',
        now(), now()
    )
ON CONFLICT (email) DO NOTHING;
