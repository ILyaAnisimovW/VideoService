package com.videoservice.upload;

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
class UploadRepository {
    private static final String COLUMNS = "id,video_id,state,source_key,provider_upload_id,expected_size_bytes," +
            "part_size_bytes,content_type,completion_parts::text AS completion_parts,processing_job_id," +
            "create_key,create_hash,completion_key,completion_hash,expires_at,updated_at";
    private final JdbcTemplate jdbc;

    UploadSessionData insert(UUID videoId, String sourceKey, long sizeBytes, String contentType,
                             String createKey, String createHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO upload_sessions(id,video_id,state,source_key,expected_size_bytes,content_type," +
                        "create_key,create_hash,expires_at) VALUES (?,?,'INITIATING',?,?,?,?,?,now()+interval '24 hours')",
                id, videoId, sourceKey, sizeBytes, contentType, createKey, createHash);
        return find(id).orElseThrow();
    }

    Optional<UploadSessionData> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM upload_sessions WHERE id=?", (rs, row) -> map(rs), id)
                .stream().findFirst();
    }

    Optional<UploadSessionData> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM upload_sessions WHERE id=? FOR UPDATE", (rs, row) -> map(rs), id)
                .stream().findFirst();
    }

    Optional<UploadSessionData> findActiveForVideo(UUID videoId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM upload_sessions WHERE video_id=? " +
                        "AND state IN ('INITIATING','OPEN','COMPLETING')", (rs, row) -> map(rs), videoId)
                .stream().findFirst();
    }

    Optional<UploadSessionData> findLatestForVideo(UUID videoId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM upload_sessions WHERE video_id=? ORDER BY updated_at DESC LIMIT 1",
                (rs, row) -> map(rs), videoId).stream().findFirst();
    }

    void claimStaleInitiating(UUID id, Instant cutoff) {
        int changed = jdbc.update("UPDATE upload_sessions SET updated_at=now() WHERE id=? AND state='INITIATING' " +
                "AND updated_at<?", id, Timestamp.from(cutoff));
        if (changed == 0) {
            throw new IllegalStateException("Initiating session was already claimed");
        }
    }

    void markOpen(UUID id, String uploadId) {
        jdbc.update("UPDATE upload_sessions SET state='OPEN',provider_upload_id=?,updated_at=now() " +
                "WHERE id=? AND state='INITIATING'", uploadId, id);
    }

    void markCompleting(UUID id, String partsJson, String key, String hash) {
        jdbc.update("UPDATE upload_sessions SET state='COMPLETING',completion_parts=?::jsonb," +
                "completion_key=?,completion_hash=?,updated_at=now() WHERE id=? AND state='OPEN'", partsJson, key, hash, id);
    }

    void markCompleted(UUID id, UUID jobId) {
        jdbc.update("UPDATE upload_sessions SET state='COMPLETED',processing_job_id=?,updated_at=now() " +
                "WHERE id=? AND state='COMPLETING'", jobId, id);
    }

    void markStopped(UUID id, String state) {
        jdbc.update("UPDATE upload_sessions SET state=?,updated_at=now() WHERE id=? " +
                "AND state IN ('INITIATING','OPEN','COMPLETING')", state, id);
    }

    List<UploadSessionData> expired() {
        return jdbc.query("SELECT " + COLUMNS + " FROM upload_sessions WHERE " +
                "(state IN ('INITIATING','OPEN') AND expires_at<now()) OR " +
                "(state='COMPLETING' AND expires_at<now()-interval '20 minutes') " +
                "ORDER BY expires_at LIMIT 25", (rs, row) -> map(rs));
    }

    private UploadSessionData map(ResultSet rs) throws SQLException {
        return new UploadSessionData((UUID) rs.getObject("id"), (UUID) rs.getObject("video_id"), rs.getString("state"),
                rs.getString("source_key"), rs.getString("provider_upload_id"), rs.getLong("expected_size_bytes"),
                rs.getLong("part_size_bytes"), rs.getString("content_type"), rs.getString("completion_parts"),
                (UUID) rs.getObject("processing_job_id"), rs.getString("create_key"), rs.getString("create_hash"),
                rs.getString("completion_key"), rs.getString("completion_hash"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
