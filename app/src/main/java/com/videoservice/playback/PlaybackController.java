package com.videoservice.playback;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PlaybackController {
    private final PlaybackService playback;

    @PostMapping("/api/v1/videos/{videoId}/playback-sessions")
    public ResponseEntity<PlaybackSessionView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        UUID viewerId = jwt == null ? null : UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer").body(playback.create(viewerId, videoId));
    }

    @GetMapping("/api/v1/playback-sessions/{sessionId}/manifests/{assetId}")
    public ResponseEntity<String> manifest(@PathVariable UUID sessionId, @PathVariable UUID assetId,
                                           @RequestParam(required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
                .body(playback.render(sessionId, assetId, token));
    }
}
