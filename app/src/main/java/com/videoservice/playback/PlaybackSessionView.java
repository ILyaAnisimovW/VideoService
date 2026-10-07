package com.videoservice.playback;

import java.time.Instant;
import java.util.UUID;

public record PlaybackSessionView(UUID id, UUID videoId, String manifestUrl, Instant expiresAt) {
}
