package com.videoservice.deletion;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class DeletionController {
    private final DeletionService deletion;

    @DeleteMapping("/api/v1/videos/{videoId}")
    public ResponseEntity<DeletionTaskView> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        DeletionTaskView task = deletion.request(UUID.fromString(jwt.getSubject()), videoId);
        if (task == null) return ResponseEntity.noContent().build();
        return ResponseEntity.accepted().location(URI.create("/api/v1/videos/" + videoId + "/deletion"))
                .body(task);
    }

    @GetMapping("/api/v1/videos/{videoId}/deletion")
    public DeletionTaskView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID videoId) {
        return deletion.get(UUID.fromString(jwt.getSubject()), videoId);
    }
}
