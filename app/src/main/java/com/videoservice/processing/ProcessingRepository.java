package com.videoservice.processing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ProcessingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ProcessingJobView enqueue(UUID videoId, int version, String sourceKey, String traceId) {
        UUID jobId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO processing_jobs(id,video_id,processing_version,state,attempt_no,max_attempts,source_key,created_at,updated_at) " +
                        "VALUES (?,?,?,'QUEUED',0,3,?,?,?)", jobId, videoId, version, sourceKey,
                Timestamp.from(now), Timestamp.from(now));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "VideoProcessingRequested");
        event.put("schemaVersion", 1);
        event.put("occurredAt", now);
        event.put("traceId", traceId);
        event.put("videoId", videoId);
        event.put("jobId", jobId);
        event.put("processingVersion", version);
        try {
            jdbc.update("INSERT INTO outbox_events(event_id,processing_job_id,event_type,schema_version,payload) " +
                            "VALUES (?,?,?,1,?::jsonb)", eventId, jobId, "VideoProcessingRequested", mapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize processing event", ex);
        }
        return find(jobId).orElseThrow();
    }

    Optional<ProcessingJobView> find(UUID id) {
        return jdbc.query("SELECT id,video_id,processing_version,state,attempt_no,max_attempts,last_error_code,created_at,updated_at " +
                        "FROM processing_jobs WHERE id=?", (rs, row) -> map(rs), id).stream().findFirst();
    }

    Optional<ProcessingJobData> data(UUID id, boolean lock) {
        String sql = "SELECT id,video_id,processing_version,state,attempt_no,max_attempts,worker_id," +
                "lease_id,lease_expires_at,attempt_started_at,source_key,updated_at FROM processing_jobs WHERE id=?" +
                (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> new ProcessingJobData((UUID) rs.getObject("id"),
                (UUID) rs.getObject("video_id"), rs.getInt("processing_version"), rs.getString("state"),
                rs.getInt("attempt_no"), rs.getInt("max_attempts"), rs.getString("worker_id"),
                (UUID) rs.getObject("lease_id"), nullableTime(rs, "lease_expires_at"),
                nullableTime(rs, "attempt_started_at"), rs.getString("source_key"),
                rs.getTimestamp("updated_at").toInstant()), id).stream().findFirst();
    }

    void start(UUID id, String workerId, UUID leaseId) {
        jdbc.update("UPDATE processing_jobs SET state='RUNNING',attempt_no=attempt_no+1,worker_id=?,lease_id=?," +
                "attempt_started_at=now(),lease_expires_at=now()+interval '60 seconds',updated_at=now() WHERE id=?",
                workerId, leaseId, id);
    }

    void heartbeat(UUID id, Instant deadline) {
        jdbc.update("UPDATE processing_jobs SET lease_expires_at=LEAST(now()+interval '60 seconds',?)," +
                "updated_at=now() WHERE id=?", Timestamp.from(deadline), id);
    }

    void requeue(UUID id, String errorCode) {
        jdbc.update("UPDATE processing_jobs SET state='QUEUED',worker_id=NULL,lease_id=NULL,lease_expires_at=NULL," +
                "attempt_started_at=NULL,last_error_code=?,updated_at=now() WHERE id=?", errorCode, id);
    }

    void finish(UUID id, String state, String errorCode) {
        jdbc.update("UPDATE processing_jobs SET state=?,last_error_code=?,lease_expires_at=NULL,updated_at=now() WHERE id=?",
                state, errorCode, id);
    }

    void cancelForVideo(UUID videoId) {
        jdbc.update("UPDATE processing_jobs SET state='CANCELLED',lease_expires_at=NULL,updated_at=now() " +
                "WHERE video_id=? AND state IN ('QUEUED','RUNNING')", videoId);
    }

    Optional<ProcessingJobData> currentForVideo(UUID videoId) {
        return jdbc.query("SELECT id FROM processing_jobs WHERE video_id=? ORDER BY processing_version DESC LIMIT 1",
                (rs, row) -> (UUID) rs.getObject("id"), videoId).stream().findFirst()
                .flatMap(id -> data(id, false));
    }

    void enqueueAgain(ProcessingJobData job, String traceId) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", eventId);
        event.put("eventType", "VideoProcessingRequested");
        event.put("schemaVersion", 1);
        event.put("occurredAt", Instant.now());
        event.put("traceId", traceId);
        event.put("videoId", job.videoId());
        event.put("jobId", job.id());
        event.put("processingVersion", job.processingVersion());
        try {
            jdbc.update("INSERT INTO outbox_events(event_id,processing_job_id,event_type,schema_version,payload) " +
                            "VALUES (?,?,?,1,?::jsonb)", eventId, job.id(), "VideoProcessingRequested",
                    mapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize processing event", ex);
        }
    }

    List<UUID> recoveryCandidates() {
        return jdbc.query("SELECT id FROM processing_jobs WHERE " +
                        "(state='RUNNING' AND lease_expires_at<now()) OR " +
                        "(state='QUEUED' AND updated_at<now()-interval '60 seconds') " +
                        "ORDER BY updated_at LIMIT 25",
                (rs, row) -> (UUID) rs.getObject("id"));
    }

    void markDispatched(UUID id) {
        jdbc.update("UPDATE processing_jobs SET updated_at=now() WHERE id=?", id);
    }

    Optional<String> eventDisposition(UUID eventId) {
        return jdbc.query("SELECT disposition FROM processed_events WHERE consumer_name='processing.results' AND event_id=?",
                (rs, row) -> rs.getString("disposition"), eventId).stream().findFirst();
    }

    void recordEvent(UUID eventId, String disposition) {
        jdbc.update("INSERT INTO processed_events(consumer_name,event_id,disposition) " +
                        "VALUES ('processing.results',?,?) ON CONFLICT DO NOTHING", eventId, disposition);
    }

    private Instant nullableTime(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private ProcessingJobView map(ResultSet rs) throws SQLException {
        return new ProcessingJobView((UUID) rs.getObject("id"), (UUID) rs.getObject("video_id"),
                rs.getInt("processing_version"), rs.getString("state"), rs.getInt("attempt_no"),
                rs.getInt("max_attempts"), rs.getString("last_error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
