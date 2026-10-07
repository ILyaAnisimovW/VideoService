package com.videoservice.catalog;

import java.time.Instant;
import java.util.UUID;

public record VideoView(UUID id, UUID ownerId, String title, String description, Visibility visibility,
                        String processingStatus, String moderationStatus, String lifecycleStatus,
                        int processingVersion, long rowVersion, Integer durationMs,
                        Instant createdAt, Instant updatedAt) {
}
