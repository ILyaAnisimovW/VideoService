package com.videoservice.processing;

import java.time.Instant;
import java.util.UUID;

public record ProcessingJobView(UUID id, UUID videoId, int processingVersion, String state,
                                int attemptNo, int maxAttempts, String lastErrorCode,
                                Instant createdAt, Instant updatedAt) {
}
