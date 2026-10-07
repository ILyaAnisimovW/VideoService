package com.videoservice.upload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadSessionView(UUID id, UUID videoId, String state, long sizeBytes,
                                long partSizeBytes, int partCount, Instant expiresAt,
                                List<StorageGateway.RemotePart> uploadedParts, UUID processingJobId) {
}
