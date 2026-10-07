package com.videoservice.catalog;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVideoRequest(@NotBlank @Size(max = 120) String title,
                                 @Size(max = 5000) String description,
                                 @NotNull Visibility visibility) {
}
