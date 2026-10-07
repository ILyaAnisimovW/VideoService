package com.videoservice.processing;

import com.videoservice.upload.StorageGateway;

import java.time.Instant;
import java.util.UUID;

public record ProcessingLease(UUID jobId, UUID videoId, int processingVersion, int attemptNo,
                              UUID leaseId, Instant expiresAt, Instant absoluteDeadline,
                              String sourceKey, String outputPrefix, int maxDurationSeconds,
                              StorageGateway.StorageAccess storageAccess) {
}
