package com.videoservice.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {
    private final VideoCatalog catalog;

    @PostMapping
    public ResponseEntity<VideoView> create(@AuthenticationPrincipal Jwt jwt,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                            @Valid @RequestBody CreateVideoRequest request) {
        VideoView video = catalog.create(UUID.fromString(jwt.getSubject()), key, request);
        return ResponseEntity.created(URI.create("/api/v1/videos/" + video.id()))
                .eTag(Long.toString(video.rowVersion())).body(video);
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        UUID actorId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        VideoView video = catalog.getVisible(actorId, videoId);
        return ResponseEntity.ok().eTag(Long.toString(video.rowVersion())).body(video);
    }

    @GetMapping
    public VideoPage list(@RequestParam(defaultValue = "20") int limit,
                          @RequestParam(required = false) String cursor) {
        return catalog.listPublic(limit, cursor);
    }

    @PatchMapping("/{videoId}")
    public ResponseEntity<VideoView> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId,
                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                           @RequestBody JsonNode request) {
        VideoView video = catalog.patch(UUID.fromString(jwt.getSubject()), videoId, ifMatch, request);
        return ResponseEntity.ok().eTag(Long.toString(video.rowVersion())).body(video);
    }
}
