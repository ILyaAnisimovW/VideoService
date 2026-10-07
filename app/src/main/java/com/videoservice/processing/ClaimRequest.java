package com.videoservice.processing;

import jakarta.validation.constraints.Min;

public record ClaimRequest(@Min(1) int processingVersion) {
}
