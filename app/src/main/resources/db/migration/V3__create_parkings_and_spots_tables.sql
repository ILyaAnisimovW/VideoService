-- V3__create_parkings_and_spots_tables.sql

CREATE TABLE parkings
(
    id              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    provider_id     UUID          NOT NULL REFERENCES users (id),
    name            VARCHAR(120)  NOT NULL,
    address         VARCHAR(255)  NOT NULL,
    lat             DOUBLE PRECISION NOT NULL,
    lng             DOUBLE PRECISION NOT NULL,
    price_per_hour  DECIMAL(10,2) NOT NULL,
    currency        VARCHAR(10)   NOT NULL DEFAULT 'RUB',
    description     TEXT,
    open_time       TIME,
    close_time      TIME,
    is_24h          BOOLEAN       NOT NULL DEFAULT FALSE,
    amenities       TEXT[]        NOT NULL DEFAULT '{}',
    status          VARCHAR(20)   NOT NULL DEFAULT 'UNDER_REVIEW',
    total_spots     INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT parkings_status_check CHECK (status IN ('ACTIVE', 'INACTIVE', 'UNDER_REVIEW'))
);

CREATE INDEX idx_parkings_provider_id ON parkings (provider_id);
CREATE INDEX idx_parkings_status      ON parkings (status);
-- Геоиндекс для поиска в радиусе
CREATE INDEX idx_parkings_location    ON parkings (lat, lng);

-- ─── Фотографии стоянок ───────────────────────────────────────────────────────
CREATE TABLE parking_photos
(
    id          UUID      NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    parking_id  UUID      NOT NULL REFERENCES parkings (id) ON DELETE CASCADE,
    s3_key      VARCHAR(500) NOT NULL,   -- ключ объекта в S3
    is_primary  BOOLEAN   NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT parking_photos_s3_key_unique UNIQUE (s3_key)
);

CREATE INDEX idx_parking_photos_parking_id ON parking_photos (parking_id);

-- ─── Парковочные места ────────────────────────────────────────────────────────
CREATE TABLE spots
(
    id             UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    parking_id     UUID          NOT NULL REFERENCES parkings (id) ON DELETE CASCADE,
    number         VARCHAR(20)   NOT NULL,
    type           VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    status         VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    price_per_hour DECIMAL(10,2),
    floor          INTEGER,
    created_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT spots_number_unique_per_parking UNIQUE (parking_id, number),
    CONSTRAINT spots_type_check   CHECK (type   IN ('STANDARD','COMPACT','EV','DISABLED','TRUCK')),
    CONSTRAINT spots_status_check CHECK (status IN ('AVAILABLE','OCCUPIED','MAINTENANCE','RESERVED'))
);

CREATE INDEX idx_spots_parking_id ON spots (parking_id);
CREATE INDEX idx_spots_status     ON spots (status);
