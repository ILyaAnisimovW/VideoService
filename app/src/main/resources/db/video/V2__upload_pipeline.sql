CREATE TABLE videos (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users(id),
    title VARCHAR(120) NOT NULL,
    description TEXT,
    visibility VARCHAR(20) NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'WAITING_UPLOAD',
    moderation_status VARCHAR(20) NOT NULL DEFAULT 'CLEAR',
    lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    processing_version INTEGER NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    duration_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT videos_title_check CHECK (length(btrim(title)) BETWEEN 1 AND 120),
    CONSTRAINT videos_visibility_check CHECK (visibility IN ('PUBLIC','UNLISTED','PRIVATE')),
    CONSTRAINT videos_processing_check CHECK (processing_status IN ('WAITING_UPLOAD','QUEUED','PROCESSING','READY','FAILED')),
    CONSTRAINT videos_moderation_check CHECK (moderation_status IN ('CLEAR','BLOCKED')),
    CONSTRAINT videos_lifecycle_check CHECK (lifecycle_status IN ('ACTIVE','DELETING','DELETED')),
    CONSTRAINT videos_version_check CHECK (processing_version >= 1 AND row_version >= 0)
);
CREATE INDEX videos_owner_created_idx ON videos(owner_id, created_at DESC, id DESC);
CREATE INDEX videos_public_idx ON videos(created_at DESC, id DESC)
    WHERE visibility='PUBLIC' AND processing_status='READY' AND moderation_status='CLEAR' AND lifecycle_status='ACTIVE';

CREATE TABLE upload_sessions (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    state VARCHAR(20) NOT NULL,
    source_key VARCHAR(500) NOT NULL UNIQUE,
    provider_upload_id VARCHAR(500),
    expected_size_bytes BIGINT NOT NULL CHECK (expected_size_bytes BETWEEN 1 AND 2147483648),
    part_size_bytes BIGINT NOT NULL DEFAULT 16777216,
    content_type VARCHAR(50) NOT NULL,
    completion_parts JSONB,
    processing_job_id UUID,
    create_key VARCHAR(128) NOT NULL,
    create_hash VARCHAR(64) NOT NULL,
    completion_key VARCHAR(128),
    completion_hash VARCHAR(64),
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT upload_state_check CHECK (state IN ('INITIATING','OPEN','COMPLETING','COMPLETED','ABORTED','EXPIRED'))
);
CREATE UNIQUE INDEX upload_one_live_per_video_idx ON upload_sessions(video_id)
    WHERE state IN ('INITIATING','OPEN','COMPLETING');
CREATE INDEX upload_expiry_idx ON upload_sessions(state, expires_at);

CREATE TABLE processing_jobs (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    processing_version INTEGER NOT NULL CHECK (processing_version >= 1),
    state VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    attempt_no INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    worker_id VARCHAR(120),
    lease_id UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_started_at TIMESTAMPTZ,
    source_key VARCHAR(500) NOT NULL,
    last_error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(video_id, processing_version),
    UNIQUE(id, video_id, processing_version),
    CONSTRAINT processing_job_state_check CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    CONSTRAINT processing_attempts_check CHECK (attempt_no BETWEEN 0 AND max_attempts)
);
CREATE INDEX processing_job_lease_idx ON processing_jobs(state, lease_expires_at);
ALTER TABLE upload_sessions ADD CONSTRAINT upload_job_fk FOREIGN KEY(processing_job_id) REFERENCES processing_jobs(id);

CREATE TABLE video_assets (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    processing_job_id UUID,
    processing_version INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL DEFAULT 0,
    kind VARCHAR(30) NOT NULL,
    quality VARCHAR(20),
    object_key VARCHAR(500) NOT NULL UNIQUE,
    rendition_prefix VARCHAR(500),
    segment_count INTEGER,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(processing_job_id, video_id, processing_version)
        REFERENCES processing_jobs(id, video_id, processing_version),
    CONSTRAINT video_asset_kind_check CHECK (kind IN ('SOURCE','MASTER_PLAYLIST','MEDIA_PLAYLIST','THUMBNAIL'))
);
CREATE UNIQUE INDEX video_one_source_idx ON video_assets(video_id) WHERE kind='SOURCE';

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    processing_job_id UUID NOT NULL REFERENCES processing_jobs(id),
    event_type VARCHAR(80) NOT NULL,
    schema_version INTEGER NOT NULL DEFAULT 1,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    publish_attempts INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX outbox_pending_idx ON outbox_events(next_attempt_at, created_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_records (
    user_id UUID NOT NULL REFERENCES users(id),
    operation VARCHAR(120) NOT NULL,
    key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    response_headers JSONB,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id, operation, key),
    CONSTRAINT idempotency_state_check CHECK (state IN ('IN_PROGRESS','COMPLETED'))
);
CREATE INDEX idempotency_expiry_idx ON idempotency_records(expires_at);

CREATE TABLE deletion_tasks (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL UNIQUE REFERENCES videos(id),
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT deletion_state_check CHECK (state IN ('PENDING','RUNNING','DONE','FAILED'))
);
CREATE INDEX deletion_pending_idx ON deletion_tasks(state, next_attempt_at);
