package com.videoservice.processing;

import java.time.Instant;

public record LeaseState(String jobState, Instant expiresAt) {
}
