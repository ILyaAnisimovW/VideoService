package com.videoservice.upload;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateUploadRequest(@Min(1) @Max(2147483648L) long sizeBytes,
                                  @NotNull @Pattern(regexp = "video/mp4|video/quicktime|video/webm") String contentType) {
}
