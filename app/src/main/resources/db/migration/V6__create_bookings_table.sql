-- V4__create_bookings_table.sql

CREATE TABLE bookings
(
    id            UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users (id),
    spot_id       UUID          NOT NULL REFERENCES spots (id),
    parking_id    UUID          NOT NULL REFERENCES parkings (id),
    status        VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    start_at      TIMESTAMP     NOT NULL,
    end_at        TIMESTAMP     NOT NULL,
    total_amount  DECIMAL(10,2) NOT NULL,
    currency      VARCHAR(10)   NOT NULL DEFAULT 'RUB',
    vehicle_plate VARCHAR(20),
    comment       TEXT,
    payment_id    UUID,                  -- заполняется после успешной оплаты
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT bookings_status_check CHECK (
        status IN ('PENDING','CONFIRMED','CANCELLED','COMPLETED','NO_SHOW')
    ),
    CONSTRAINT bookings_dates_check CHECK (end_at > start_at)
);

CREATE INDEX idx_bookings_user_id    ON bookings (user_id);
CREATE INDEX idx_bookings_spot_id    ON bookings (spot_id);
CREATE INDEX idx_bookings_parking_id ON bookings (parking_id);
CREATE INDEX idx_bookings_status     ON bookings (status);
-- Индекс для быстрой проверки пересечений по времени
CREATE INDEX idx_bookings_time_range ON bookings (spot_id, start_at, end_at);
