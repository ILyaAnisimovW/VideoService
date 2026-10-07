package com.videoservice.worker;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class WorkerMessages {
    private WorkerMessages() { }

    record Requested(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                     String traceId, UUID videoId, UUID jobId, int processingVersion) { }

    record StorageAccess(String endpoint, String bucket, String region, String accessKeyId,
                         String secretAccessKey, String sessionToken, Instant expiresAt) { }

    record Lease(UUID jobId, UUID videoId, int processingVersion, int attemptNo, UUID leaseId,
                 Instant expiresAt, Instant absoluteDeadline, String sourceKey, String outputPrefix,
                 int maxDurationSeconds, StorageAccess storageAccess) { }

    record LeaseState(String jobState, Instant expiresAt) { }

    record Asset(String kind, String quality, String objectKey, String renditionPrefix,
                 Integer segmentCount, long sizeBytes) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Result(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                  String traceId, UUID videoId, UUID jobId, int processingVersion,
                  int attemptNo, UUID leaseId, Integer durationMs, List<Asset> assets,
                  String errorCode, Boolean retryable) { }
}
