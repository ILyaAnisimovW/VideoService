package com.videoservice.upload;

import java.time.Instant;
import java.util.UUID;

record UploadSessionData(UUID id, UUID videoId, String state, String sourceKey, String providerUploadId,
                         long sizeBytes, long partSizeBytes, String contentType, String completionParts,
                         UUID processingJobId, String createKey, String createHash, String completionKey, String completionHash,
                         Instant expiresAt, Instant updatedAt) {
    int partCount() {
        return (int) ((sizeBytes + partSizeBytes - 1) / partSizeBytes);
    }

    long expectedPartSize(int number) {
        return Math.min(partSizeBytes, sizeBytes - (long) (number - 1) * partSizeBytes);
    }
}
