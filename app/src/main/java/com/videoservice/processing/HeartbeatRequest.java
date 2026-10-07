package com.videoservice.processing;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record HeartbeatRequest(@NotNull UUID leaseId, @Min(1) int attemptNo) {
}
