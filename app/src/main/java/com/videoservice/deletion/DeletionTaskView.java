package com.videoservice.deletion;

import java.time.Instant;
import java.util.UUID;

public record DeletionTaskView(UUID id, UUID videoId, String state, Instant updatedAt) {
}
