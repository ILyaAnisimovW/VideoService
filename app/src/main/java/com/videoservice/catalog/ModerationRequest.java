package com.videoservice.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ModerationRequest(@NotNull ModerationStatus status,
                                @NotBlank @Size(max = 1000) String reason) {
}
