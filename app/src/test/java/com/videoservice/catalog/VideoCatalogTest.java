package com.videoservice.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoservice.auth.dto.UserResponse;
import com.videoservice.auth.entity.UserRole;
import com.videoservice.auth.service.AuthService;
import com.videoservice.infrastructure.IdempotencyStore;
import com.videoservice.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoCatalogTest {
    private final VideoRepository videos = mock(VideoRepository.class);
    private final AuthService identity = mock(AuthService.class);
    private final VideoCatalog catalog = new VideoCatalog(videos, identity, mock(IdempotencyStore.class));
    private final UUID ownerId = UUID.randomUUID();
    private final UUID videoId = UUID.randomUUID();

    @Test
    void patchRejectsStaleEtagAndDoesNotLoseChanges() throws Exception {
        when(videos.lock(videoId)).thenReturn(Optional.of(video(Visibility.PRIVATE, "ACTIVE", "CLEAR")));
        when(identity.currentUser(ownerId)).thenReturn(user(ownerId));
        ApiException error = assertThrows(ApiException.class,
                () -> catalog.patch(ownerId, videoId, "\"6\"", new ObjectMapper().readTree("{\"title\":\"new\"}")));
        assertEquals("PRECONDITION_FAILED", error.getCode());
        verify(videos, never()).patch(any(), any(), any(), any());
    }

    @Test
    void patchCanClearDescriptionWithoutChangingOtherFields() throws Exception {
        VideoView old = video(Visibility.PRIVATE, "ACTIVE", "CLEAR");
        when(videos.lock(videoId)).thenReturn(Optional.of(old));
        when(videos.find(videoId)).thenReturn(Optional.of(old));
        when(identity.currentUser(ownerId)).thenReturn(user(ownerId));
        catalog.patch(ownerId, videoId, "\"7\"", new ObjectMapper().readTree("{\"description\":null}"));
        verify(videos).patch(videoId, "title", null, Visibility.PRIVATE);
    }

    @Test
    void privateVideoIsHiddenFromStrangerAndBlockedFromOwner() {
        when(videos.find(videoId)).thenReturn(Optional.of(video(Visibility.PRIVATE, "ACTIVE", "CLEAR")));
        assertEquals("NOT_FOUND", assertThrows(ApiException.class,
                () -> catalog.requirePlayable(UUID.randomUUID(), videoId)).getCode());
        when(videos.find(videoId)).thenReturn(Optional.of(video(Visibility.PRIVATE, "ACTIVE", "BLOCKED")));
        assertEquals("VIDEO_BLOCKED", assertThrows(ApiException.class,
                () -> catalog.requirePlayable(ownerId, videoId)).getCode());
    }

    private VideoView video(Visibility visibility, String lifecycle, String moderation) {
        return new VideoView(videoId, ownerId, "title", "old", visibility, "READY", moderation,
                lifecycle, 1, 7, 60000, Instant.now(), Instant.now());
    }

    private UserResponse user(UUID id) {
        return new UserResponse(id, "owner@example.com", "Owner", UserRole.USER, Instant.now());
    }
}
