package com.videoservice.playback;

import com.videoservice.auth.service.AuthService;
import com.videoservice.catalog.VideoCatalog;
import com.videoservice.shared.exception.ApiException;
import com.videoservice.upload.StorageGateway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PlaybackServiceTest {
    @Test
    void wrongTokenCannotReadPlaylistOrStorage() throws Exception {
        PlaybackRepository sessions = mock(PlaybackRepository.class);
        VideoCatalog catalog = mock(VideoCatalog.class);
        StorageGateway storage = mock(StorageGateway.class);
        PlaybackService playback = new PlaybackService(sessions, catalog, storage, mock(AuthService.class));
        UUID sessionId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("correct".getBytes(StandardCharsets.UTF_8)));
        when(sessions.find(sessionId)).thenReturn(Optional.of(new PlaybackSessionData(sessionId, videoId,
                null, hash, 1, Instant.now().plusSeconds(60), null)));
        ApiException error = assertThrows(ApiException.class,
                () -> playback.render(sessionId, UUID.randomUUID(), "incorrect"));
        assertEquals("INVALID_PLAYBACK_TOKEN", error.getCode());
        verifyNoInteractions(storage, catalog);
    }
}
