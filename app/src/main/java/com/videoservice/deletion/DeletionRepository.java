package com.videoservice.deletion;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class DeletionRepository {
    private final JdbcTemplate jdbc;

    DeletionTaskView create(UUID videoId) {
        jdbc.update("INSERT INTO deletion_tasks(id,video_id,state,next_attempt_at) " +
                "VALUES (?,?,'PENDING',now()+interval '30 minutes') ON CONFLICT(video_id) DO NOTHING",
                UUID.randomUUID(), videoId);
        return find(videoId).orElseThrow();
    }

    Optional<DeletionTaskView> find(UUID videoId) {
        return jdbc.query("SELECT id,video_id,state,updated_at FROM deletion_tasks WHERE video_id=?",
                (rs, row) -> new DeletionTaskView((UUID) rs.getObject("id"), (UUID) rs.getObject("video_id"),
                        rs.getString("state"), rs.getTimestamp("updated_at").toInstant()), videoId)
                .stream().findFirst();
    }

    List<UUID> claimReady() {
        return jdbc.query("UPDATE deletion_tasks SET state='RUNNING',updated_at=now() " +
                        "WHERE id IN (SELECT id FROM deletion_tasks WHERE " +
                        "(state='PENDING' AND next_attempt_at<=now()) OR " +
                        "(state='RUNNING' AND updated_at<now()-interval '10 minutes') " +
                        "ORDER BY next_attempt_at LIMIT 10 FOR UPDATE SKIP LOCKED) RETURNING video_id",
                (rs, row) -> (UUID) rs.getObject("video_id"));
    }

    void complete(UUID videoId) {
        jdbc.update("UPDATE deletion_tasks SET state='DONE',updated_at=now() WHERE video_id=?", videoId);
    }

    void retryOrFail(UUID videoId) {
        jdbc.update("UPDATE deletion_tasks SET attempts=attempts+1, " +
                        "state=CASE WHEN attempts+1>=10 THEN 'FAILED' ELSE 'PENDING' END," +
                        "next_attempt_at=now()+make_interval(secs => LEAST(3600, POWER(2, LEAST(attempts, 10))::int))," +
                        "last_error='cleanup failed',updated_at=now() WHERE video_id=?", videoId);
    }
}
