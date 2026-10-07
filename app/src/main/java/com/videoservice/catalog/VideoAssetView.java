package com.videoservice.catalog;

import java.util.UUID;

public record VideoAssetView(UUID id, UUID videoId, UUID processingJobId, int processingVersion,
                             int attemptNo, String kind, String quality, String objectKey,
                             String renditionPrefix, Integer segmentCount, long sizeBytes) {
}
