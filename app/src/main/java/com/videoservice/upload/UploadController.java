package com.videoservice.upload;

import com.videoservice.processing.ProcessingJobView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UploadController {
    private final UploadService uploads;

    @PostMapping("/videos/{videoId}/upload-sessions")
    public ResponseEntity<UploadSessionView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                     @Valid @RequestBody CreateUploadRequest request) {
        UploadSessionView session = uploads.create(actor(jwt), videoId, key, request);
        return ResponseEntity.created(URI.create("/api/v1/upload-sessions/" + session.id()))
                .cacheControl(CacheControl.noStore()).body(session);
    }

    @GetMapping("/upload-sessions/{sessionId}")
    public ResponseEntity<UploadSessionView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(uploads.get(actor(jwt), sessionId));
    }

    @PostMapping("/upload-sessions/{sessionId}/parts/{partNumber}/url")
    public ResponseEntity<StorageGateway.PartGrant> signPart(@AuthenticationPrincipal Jwt jwt,
                                                             @PathVariable UUID sessionId, @PathVariable int partNumber) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(uploads.signPart(actor(jwt), sessionId, partNumber));
    }

    @PostMapping("/upload-sessions/{sessionId}/complete")
    public ResponseEntity<ProcessingJobView> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @Valid @RequestBody CompleteUploadRequest request,
                                                       HttpServletRequest servletRequest) {
        ProcessingJobView job = uploads.complete(actor(jwt), sessionId, key, request,
                String.valueOf(servletRequest.getAttribute("traceId")));
        return ResponseEntity.accepted().location(URI.create("/api/v1/processing-jobs/" + job.id())).body(job);
    }

    @DeleteMapping("/upload-sessions/{sessionId}")
    public ResponseEntity<Void> abort(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        uploads.abort(actor(jwt), sessionId);
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
