ALTER TABLE videos ADD COLUMN moderation_reason TEXT;

CREATE UNIQUE INDEX video_one_master_or_thumbnail_idx ON video_assets(video_id,processing_version,attempt_no,kind)
    WHERE kind IN ('MASTER_PLAYLIST','THUMBNAIL');
CREATE UNIQUE INDEX video_one_rendition_quality_idx ON video_assets(video_id,processing_version,attempt_no,quality)
    WHERE kind='MEDIA_PLAYLIST';
ALTER TABLE video_assets ADD CONSTRAINT video_asset_quality_check CHECK
    ((kind='MEDIA_PLAYLIST' AND quality IS NOT NULL AND rendition_prefix IS NOT NULL AND segment_count > 0)
    OR (kind<>'MEDIA_PLAYLIST' AND quality IS NULL));

CREATE TABLE processed_events (
    consumer_name VARCHAR(80) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    disposition VARCHAR(20) NOT NULL,
    PRIMARY KEY(consumer_name,event_id),
    CONSTRAINT processed_event_disposition_check CHECK (disposition IN ('APPLIED','DUPLICATE','STALE','REJECTED'))
);
CREATE INDEX processed_events_age_idx ON processed_events(processed_at);

CREATE TABLE playback_sessions (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    viewer_id UUID REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    processing_version INTEGER NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX playback_sessions_expiry_idx ON playback_sessions(expires_at);
CREATE INDEX playback_sessions_video_idx ON playback_sessions(video_id) WHERE revoked_at IS NULL;
