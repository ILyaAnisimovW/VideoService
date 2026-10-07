package com.videoservice.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class VideoRepository {
    private static final String COLUMNS = "id,owner_id,title,description,visibility,processing_status,moderation_status," +
            "lifecycle_status,processing_version,row_version,duration_ms,created_at,updated_at";
    private final JdbcTemplate jdbc;

    VideoView create(UUID ownerId, CreateVideoRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO videos(id,owner_id,title,description,visibility) VALUES (?,?,?,?,?)",
                id, ownerId, request.title().trim(), request.description(), request.visibility().name());
        return find(id).orElseThrow();
    }

    Optional<VideoView> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM videos WHERE id=?", (rs, row) -> map(rs), id)
                .stream().findFirst();
    }

    Optional<VideoView> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM videos WHERE id=? FOR UPDATE", (rs, row) -> map(rs), id)
                .stream().findFirst();
    }

    List<VideoView> list(boolean owned, UUID ownerId, Instant beforeTime, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM videos WHERE ");
        sql.append(owned ? "owner_id=?" :
                "visibility='PUBLIC' AND processing_status='READY' AND moderation_status='CLEAR' AND lifecycle_status='ACTIVE'");
        if (beforeTime != null) {
            sql.append(" AND (created_at,id) < (?,?)");
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        if (owned && beforeTime != null) {
            return jdbc.query(sql.toString(), (rs, row) -> map(rs), ownerId, Timestamp.from(beforeTime), beforeId, limit);
        }
        if (owned) {
            return jdbc.query(sql.toString(), (rs, row) -> map(rs), ownerId, limit);
        }
        if (beforeTime != null) {
            return jdbc.query(sql.toString(), (rs, row) -> map(rs), Timestamp.from(beforeTime), beforeId, limit);
        }
        return jdbc.query(sql.toString(), (rs, row) -> map(rs), limit);
    }

    void patch(UUID id, String title, String description, Visibility visibility) {
        jdbc.update("UPDATE videos SET title=?,description=?,visibility=?,row_version=row_version+1,updated_at=now() WHERE id=?",
                title, description, visibility.name(), id);
    }

    void moderate(UUID id, ModerationStatus status, String reason) {
        jdbc.update("UPDATE videos SET moderation_status=?,moderation_reason=?,row_version=row_version+1,updated_at=now() WHERE id=?",
                status.name(), reason, id);
    }

    void markProcessing(UUID id) {
        jdbc.update("UPDATE videos SET processing_status='PROCESSING',row_version=row_version+1,updated_at=now() WHERE id=?",
                id);
    }

    void markReady(UUID id, int durationMs) {
        jdbc.update("UPDATE videos SET processing_status='READY',duration_ms=?,row_version=row_version+1,updated_at=now() WHERE id=?",
                durationMs, id);
    }

    void markFailed(UUID id) {
        jdbc.update("UPDATE videos SET processing_status='FAILED',row_version=row_version+1,updated_at=now() WHERE id=?", id);
    }

    void markRequeued(UUID id) {
        jdbc.update("UPDATE videos SET processing_status='QUEUED',row_version=row_version+1,updated_at=now() WHERE id=?", id);
    }

    void incrementProcessingVersion(UUID id) {
        jdbc.update("UPDATE videos SET processing_version=processing_version+1,processing_status='QUEUED'," +
                "duration_ms=NULL,row_version=row_version+1,updated_at=now() WHERE id=?", id);
    }

    void markQueued(UUID id) {
        int updated = jdbc.update("UPDATE videos SET processing_status='QUEUED', row_version=row_version+1, updated_at=now() " +
                "WHERE id=? AND lifecycle_status='ACTIVE' AND processing_status='WAITING_UPLOAD'", id);
        if (updated != 1) {
            throw new IllegalStateException("Video changed before upload completion");
        }
    }

    void insertSource(UUID videoId, int processingVersion, String sourceKey, long sizeBytes) {
        jdbc.update("INSERT INTO video_assets(id,video_id,processing_version,attempt_no,kind,object_key,size_bytes) " +
                "VALUES (?,?,?,?,?,?,?)", UUID.randomUUID(), videoId, processingVersion, 0, "SOURCE", sourceKey, sizeBytes);
    }

    void insertProcessedAsset(UUID videoId, UUID jobId, int version, int attemptNo, VideoAssetInput asset) {
        jdbc.update("INSERT INTO video_assets(id,video_id,processing_job_id,processing_version,attempt_no,kind," +
                        "quality,object_key,rendition_prefix,segment_count,size_bytes) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), videoId, jobId, version, attemptNo, asset.kind(), asset.quality(),
                asset.objectKey(), asset.renditionPrefix(), asset.segmentCount(), asset.sizeBytes());
    }

    List<VideoAssetView> acceptedAssets(UUID videoId, int version) {
        return jdbc.query("SELECT a.id,a.video_id,a.processing_job_id,a.processing_version,a.attempt_no," +
                        "a.kind,a.quality,a.object_key,a.rendition_prefix,a.segment_count,a.size_bytes " +
                        "FROM video_assets a JOIN processing_jobs j ON j.id=a.processing_job_id " +
                        "WHERE a.video_id=? AND a.processing_version=? AND j.state='SUCCEEDED' " +
                        "AND a.kind<>'SOURCE' ORDER BY a.kind,a.quality",
                (rs, row) -> new VideoAssetView((UUID) rs.getObject("id"), (UUID) rs.getObject("video_id"),
                        (UUID) rs.getObject("processing_job_id"), rs.getInt("processing_version"),
                        rs.getInt("attempt_no"), rs.getString("kind"), rs.getString("quality"),
                        rs.getString("object_key"), rs.getString("rendition_prefix"),
                        (Integer) rs.getObject("segment_count"), rs.getLong("size_bytes")), videoId, version);
    }

    void markDeleting(UUID id) {
        jdbc.update("UPDATE videos SET lifecycle_status='DELETING',row_version=row_version+1,updated_at=now() " +
                "WHERE id=? AND lifecycle_status='ACTIVE'", id);
    }

    void markDeleted(UUID id) {
        jdbc.update("UPDATE videos SET lifecycle_status='DELETED',row_version=row_version+1,deleted_at=now(),updated_at=now() " +
                "WHERE id=? AND lifecycle_status='DELETING'", id);
    }

    private VideoView map(ResultSet rs) throws SQLException {
        return new VideoView((UUID) rs.getObject("id"), (UUID) rs.getObject("owner_id"),
                rs.getString("title"), rs.getString("description"), Visibility.valueOf(rs.getString("visibility")),
                rs.getString("processing_status"), rs.getString("moderation_status"), rs.getString("lifecycle_status"),
                rs.getInt("processing_version"), rs.getLong("row_version"),
                (Integer) rs.getObject("duration_ms"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
