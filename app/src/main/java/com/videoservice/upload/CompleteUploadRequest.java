package com.videoservice.upload;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CompleteUploadRequest(@NotEmpty @Size(max = 128) List<@Valid CompletePart> parts) {
}
