package com.videoservice.processing;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProcessingOperationsController {
    private final ProcessingCoordinator processing;

    @PostMapping("/api/v1/videos/{videoId}/processing-retries")
    public ResponseEntity<ProcessingJobView> retry(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId,
                                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                   HttpServletRequest request) {
        ProcessingJobView job = processing.retry(UUID.fromString(jwt.getSubject()), videoId, key,
                String.valueOf(request.getAttribute("traceId")));
        return ResponseEntity.accepted().location(URI.create("/api/v1/processing-jobs/" + job.id())).body(job);
    }

    @PostMapping("/api/v1/internal/processing-jobs/{jobId}/claims")
    public ResponseEntity<ProcessingLease> claim(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId,
                                                 @Valid @RequestBody ClaimRequest request) {
        ProcessingLease lease = processing.claim(jobId, request.processingVersion(), jwt.getSubject());
        return ResponseEntity.created(URI.create("/api/v1/internal/processing-jobs/" + jobId + "/lease"))
                .cacheControl(CacheControl.noStore()).body(lease);
    }

    @PutMapping("/api/v1/internal/processing-jobs/{jobId}/lease")
    public ResponseEntity<LeaseState> heartbeat(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID jobId,
                                                @Valid @RequestBody HeartbeatRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(processing.heartbeat(jobId, request.leaseId(), request.attemptNo(), jwt.getSubject()));
    }
}
