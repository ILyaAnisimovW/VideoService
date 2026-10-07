package com.videoservice.processing;

import com.videoservice.catalog.VideoAssetInput;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProcessingResultEvent(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                                    String traceId, UUID videoId, UUID jobId, int processingVersion,
                                    int attemptNo, UUID leaseId, Integer durationMs,
                                    List<VideoAssetInput> assets, String errorCode, Boolean retryable) {
}
