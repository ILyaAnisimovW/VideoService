package com.videoservice.catalog;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class VideoDiscoveryController {
    private final VideoCatalog catalog;

    @GetMapping("/api/v1/users/me/videos")
    public VideoPage myVideos(@AuthenticationPrincipal Jwt jwt,
                              @RequestParam(defaultValue = "20") int limit,
                              @RequestParam(required = false) String cursor) {
        return catalog.listOwned(UUID.fromString(jwt.getSubject()), limit, cursor);
    }

    @PutMapping("/api/v1/videos/{videoId}/moderation")
    public ResponseEntity<VideoView> moderate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                              @Valid @RequestBody ModerationRequest request) {
        VideoView video = catalog.moderate(UUID.fromString(jwt.getSubject()), videoId, ifMatch, request);
        return ResponseEntity.ok().eTag(Long.toString(video.rowVersion())).body(video);
    }
}
