package com.videoservice.processing;

import com.videoservice.catalog.VideoCatalog;
import com.videoservice.catalog.VideoView;
import com.videoservice.infrastructure.IdempotencyStore;
import com.videoservice.upload.StorageGateway;
import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProcessingCoordinator {
    private final ProcessingRepository jobs;
    private final VideoCatalog catalog;
    private final StorageGateway storage;
    private final IdempotencyStore idempotency;
    private final AssetVerifier verifier;
    private final TransactionTemplate tx;

    public ProcessingCoordinator(ProcessingRepository jobs, VideoCatalog catalog, StorageGateway storage,
                                 IdempotencyStore idempotency, AssetVerifier verifier, PlatformTransactionManager manager) {
        this.jobs = jobs;
        this.catalog = catalog;
        this.storage = storage;
        this.idempotency = idempotency;
        this.verifier = verifier;
        this.tx = new TransactionTemplate(manager);
    }

    /** Joins the upload completion transaction. */
    public ProcessingJobView enqueue(VideoView video, String sourceKey, String traceId) {
        return jobs.enqueue(video.id(), video.processingVersion(), sourceKey, traceId);
    }

    @Transactional(readOnly = true)
    public ProcessingJobView getOwned(UUID actorId, UUID jobId) {
        ProcessingJobView job = jobs.find(jobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задание не найдено"));
        catalog.requireOwned(actorId, job.videoId());
        return job;
    }

    public ProcessingJobView get(UUID jobId) {
        return jobs.find(jobId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задание не найдено"));
    }

    public ProcessingLease claim(UUID jobId, int version, String workerId) {
        ProcessingJobData snapshot = jobs.data(jobId, false).orElseThrow(this::missing);
        ProcessingJobData claimed = Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(snapshot.videoId());
            ProcessingJobData job = jobs.data(jobId, true).orElseThrow(this::missing);
            if (!video.lifecycleStatus().equals("ACTIVE")) throw conflict("JOB_CANCELLED");
            if (job.processingVersion() != version || video.processingVersion() != version) throw conflict("STALE_JOB");
            if (job.state().equals("RUNNING")) throw conflict("JOB_BUSY");
            if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(job.state())) throw conflict("JOB_TERMINAL");
            if (job.attemptNo() >= job.maxAttempts()) throw conflict("ATTEMPT_LIMIT");
            jobs.start(jobId, workerId, UUID.randomUUID());
            catalog.markProcessing(video.id());
            return jobs.data(jobId, false).orElseThrow(this::missing);
        }));
        StorageGateway.StorageAccess access = storage.scopedAccess(claimed.sourceKey(), claimed.outputPrefix(),
                claimed.absoluteDeadline());
        return new ProcessingLease(claimed.id(), claimed.videoId(), claimed.processingVersion(), claimed.attemptNo(),
                claimed.leaseId(), claimed.leaseExpiresAt(), claimed.absoluteDeadline(), claimed.sourceKey(),
                claimed.outputPrefix(), 1800, access);
    }

    public LeaseState heartbeat(UUID jobId, UUID leaseId, int attemptNo, String workerId) {
        ProcessingJobData snapshot = jobs.data(jobId, false).orElseThrow(this::missing);
        return Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(snapshot.videoId());
            ProcessingJobData job = jobs.data(jobId, true).orElseThrow(this::missing);
            if (!video.lifecycleStatus().equals("ACTIVE") || video.processingVersion() != job.processingVersion()) {
                throw conflict("JOB_CANCELLED");
            }
            if (!leaseId.equals(job.leaseId()) || attemptNo != job.attemptNo() || !workerId.equals(job.workerId())) {
                throw conflict("STALE_JOB");
            }
            if (job.state().equals("SUCCEEDED") || job.state().equals("FAILED")) {
                return new LeaseState(job.state(), null);
            }
            Instant now = Instant.now();
            if (!job.state().equals("RUNNING") || job.leaseExpiresAt() == null ||
                    !job.leaseExpiresAt().isAfter(now) || !job.absoluteDeadline().isAfter(now)) {
                throw conflict("LEASE_EXPIRED");
            }
            jobs.heartbeat(jobId, job.absoluteDeadline());
            return new LeaseState("RUNNING", jobs.data(jobId, false).orElseThrow(this::missing).leaseExpiresAt());
        }));
    }

    public ProcessingJobView retry(UUID actorId, UUID videoId, String key, String traceId) {
        idempotency.validateKey(key);
        String operation = "retryProcessing:" + videoId;
        String hash = idempotency.fingerprint("POST", "/api/v1/videos/" + videoId + "/processing-retries", "");
        var replay = idempotency.replay(actorId, operation, key, hash, ProcessingJobView.class);
        if (replay.isPresent()) return replay.get();
        ProcessingJobData source = jobs.currentForVideo(videoId).orElseThrow(this::missing);
        if (storage.head(source.sourceKey()).isEmpty()) throw conflict("SOURCE_MISSING");
        return Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(videoId);
            catalog.requireOwned(actorId, videoId);
            if (!video.lifecycleStatus().equals("ACTIVE") || !video.processingStatus().equals("FAILED")) {
                throw conflict("INVALID_STATE");
            }
            ProcessingJobData previous = jobs.currentForVideo(videoId).orElseThrow(this::missing);
            if (!previous.state().equals("FAILED") || !previous.id().equals(source.id())) {
                throw conflict("SOURCE_MISSING");
            }
            catalog.incrementProcessingVersion(videoId);
            ProcessingJobView result = jobs.enqueue(videoId, video.processingVersion() + 1, previous.sourceKey(), traceId);
            idempotency.complete(actorId, operation, key, hash, 202, result,
                    "/api/v1/processing-jobs/" + result.id(), null);
            return result;
        }));
    }

    public void cancel(UUID videoId) {
        jobs.cancelForVideo(videoId);
    }

    public String applyResult(ProcessingResultEvent result) {
        validateResult(result);
        ProcessingJobData snapshot = jobs.data(result.jobId(), false).orElseThrow(this::missing);
        if (!snapshot.videoId().equals(result.videoId())) throw new IllegalArgumentException("Result job/video mismatch");
        if (jobs.eventDisposition(result.eventId()).isPresent()) return "DUPLICATE";
        if (result.eventType().equals("VideoProcessingSucceeded") && validAttempt(snapshot, result)) {
            verifier.verify(result, snapshot.outputPrefix());
        }
        return Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(snapshot.videoId());
            ProcessingJobData job = jobs.data(result.jobId(), true).orElseThrow(this::missing);
            if (jobs.eventDisposition(result.eventId()).isPresent()) return "DUPLICATE";
            String disposition;
            if (job.state().equals("SUCCEEDED") || job.state().equals("FAILED")) {
                disposition = "DUPLICATE";
            } else if (!video.lifecycleStatus().equals("ACTIVE") ||
                    video.processingVersion() != result.processingVersion() || !validAttempt(job, result)) {
                disposition = "STALE";
            } else if (result.eventType().equals("VideoProcessingSucceeded")) {
                catalog.publishAssets(video.id(), job.id(), job.processingVersion(), job.attemptNo(),
                        result.assets(), result.durationMs());
                jobs.finish(job.id(), "SUCCEEDED", null);
                disposition = "APPLIED";
            } else {
                if (Boolean.TRUE.equals(result.retryable()) && job.attemptNo() < job.maxAttempts()) {
                    jobs.requeue(job.id(), result.errorCode());
                    catalog.markRequeued(video.id());
                    jobs.enqueueAgain(job, result.traceId());
                } else {
                    jobs.finish(job.id(), "FAILED", result.errorCode());
                    catalog.markFailed(video.id());
                }
                disposition = "APPLIED";
            }
            jobs.recordEvent(result.eventId(), disposition);
            return disposition;
        }));
    }

    private boolean validAttempt(ProcessingJobData job, ProcessingResultEvent result) {
        return job.state().equals("RUNNING") && job.processingVersion() == result.processingVersion() &&
                job.attemptNo() == result.attemptNo() && result.leaseId().equals(job.leaseId()) &&
                job.leaseExpiresAt() != null && job.leaseExpiresAt().isAfter(Instant.now()) &&
                job.absoluteDeadline().isAfter(Instant.now());
    }

    private void validateResult(ProcessingResultEvent result) {
        if (result == null || result.eventId() == null || result.jobId() == null || result.videoId() == null ||
                result.leaseId() == null || result.occurredAt() == null || result.traceId() == null ||
                result.traceId().isBlank() || result.schemaVersion() != 1 || result.processingVersion() < 1 ||
                result.attemptNo() < 1 || result.attemptNo() > 3 ||
                !("VideoProcessingSucceeded".equals(result.eventType()) ||
                        "VideoProcessingFailed".equals(result.eventType()))) {
            throw new IllegalArgumentException("Invalid processing result envelope");
        }
        if (result.eventType().equals("VideoProcessingFailed") &&
                (result.errorCode() == null || !result.errorCode().matches("[A-Z0-9_]{1,60}") ||
                        result.retryable() == null || result.assets() != null || result.durationMs() != null)) {
            throw new IllegalArgumentException("Invalid processing failure");
        }
        if (result.eventType().equals("VideoProcessingSucceeded") &&
                (result.errorCode() != null || result.retryable() != null || result.assets() == null ||
                        result.durationMs() == null)) {
            throw new IllegalArgumentException("Invalid processing success");
        }
    }

    @Scheduled(fixedDelayString = "${video.processing.recovery-poll-ms:15000}")
    public void recover() {
        for (UUID jobId : jobs.recoveryCandidates()) {
            ProcessingJobData snapshot = jobs.data(jobId, false).orElse(null);
            if (snapshot == null) continue;
            tx.executeWithoutResult(status -> {
                VideoView video = catalog.lockForMaintenance(snapshot.videoId());
                ProcessingJobData job = jobs.data(jobId, true).orElseThrow(this::missing);
                if (!video.lifecycleStatus().equals("ACTIVE") || video.processingVersion() != job.processingVersion()) {
                    jobs.cancelForVideo(video.id());
                } else if (job.state().equals("RUNNING") && job.leaseExpiresAt() != null &&
                        job.leaseExpiresAt().isBefore(Instant.now())) {
                    if (job.attemptNo() >= job.maxAttempts()) {
                        jobs.finish(jobId, "FAILED", "LEASE_EXPIRED");
                        catalog.markFailed(video.id());
                    } else {
                        jobs.requeue(jobId, "LEASE_EXPIRED");
                        catalog.markRequeued(video.id());
                        jobs.enqueueAgain(job, "recovery-" + UUID.randomUUID());
                    }
                } else if (job.state().equals("QUEUED") && job.updatedAt().isBefore(Instant.now().minusSeconds(60))) {
                    jobs.enqueueAgain(job, "redispatch-" + UUID.randomUUID());
                    jobs.markDispatched(jobId);
                }
            });
        }
    }

    private ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задание не найдено");
    }

    private ApiException conflict(String code) {
        return new ApiException(HttpStatus.CONFLICT, code, "Задание недоступно");
    }
}
