package com.videoservice.processing;

import java.time.Instant;
import java.util.UUID;

record ProcessingJobData(UUID id, UUID videoId, int processingVersion, String state, int attemptNo,
                         int maxAttempts, String workerId, UUID leaseId, Instant leaseExpiresAt,
                         Instant attemptStartedAt, String sourceKey, Instant updatedAt) {
    String outputPrefix() {
        return "videos/" + videoId + "/processed/v" + processingVersion + "/" + id + "/attempt-" + attemptNo + "/";
    }

    Instant absoluteDeadline() {
        return attemptStartedAt.plusSeconds(1200);
    }
}
