-- V8__create_parking_photos.sql
-- Таблица уже создана в V3, но через неё не прошла в старой версии БД.
-- Создаём только если не существует.

CREATE TABLE IF NOT EXISTS parking_photos
(
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    parking_id  UUID         NOT NULL REFERENCES parkings (id) ON DELETE CASCADE,
    s3_key      VARCHAR(500) NOT NULL,
    is_primary  BOOLEAN      NOT NULL DEFAULT FALSE,
    uploaded_at TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT parking_photos_s3_key_unique UNIQUE (s3_key)
);

CREATE INDEX IF NOT EXISTS idx_parking_photos_parking_id ON parking_photos (parking_id);