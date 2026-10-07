package com.videoservice.playback;

import java.time.Instant;
import java.util.UUID;

record PlaybackSessionData(UUID id, UUID videoId, UUID viewerId, String tokenHash,
                           int processingVersion, Instant expiresAt, Instant revokedAt) {
}
