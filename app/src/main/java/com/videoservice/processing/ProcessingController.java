package com.videoservice.processing;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/processing-jobs")
@RequiredArgsConstructor
public class ProcessingController {
    private final ProcessingCoordinator processing;

    @GetMapping("/{jobId}")
    public ProcessingJobView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId) {
        return processing.getOwned(UUID.fromString(jwt.getSubject()), jobId);
    }

}
