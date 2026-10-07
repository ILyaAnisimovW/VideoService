package com.videoservice.upload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompletePart(@Min(1) @Max(128) int partNumber,
                           @NotBlank @Size(max = 256) String etag) {
}
