package com.videoservice.playback;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class PlaybackRepository {
    private final JdbcTemplate jdbc;

    void create(UUID id, UUID videoId, UUID viewerId, String tokenHash, int version, Instant expiresAt) {
        jdbc.update("INSERT INTO playback_sessions(id,video_id,viewer_id,token_hash,processing_version,expires_at) " +
                        "VALUES (?,?,?,?,?,?)", id, videoId, viewerId, tokenHash, version, Timestamp.from(expiresAt));
    }

    Optional<PlaybackSessionData> find(UUID id) {
        return jdbc.query("SELECT id,video_id,viewer_id,token_hash,processing_version,expires_at,revoked_at " +
                        "FROM playback_sessions WHERE id=?",
                (rs, row) -> new PlaybackSessionData((UUID) rs.getObject("id"), (UUID) rs.getObject("video_id"),
                        (UUID) rs.getObject("viewer_id"), rs.getString("token_hash"),
                        rs.getInt("processing_version"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
                id).stream().findFirst();
    }

    void revoke(UUID videoId) {
        jdbc.update("UPDATE playback_sessions SET revoked_at=now() WHERE video_id=? AND revoked_at IS NULL", videoId);
    }
}
