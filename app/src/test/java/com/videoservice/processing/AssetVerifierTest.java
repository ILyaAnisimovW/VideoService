package com.videoservice.processing;

import com.videoservice.catalog.VideoAssetInput;
import com.videoservice.upload.StorageGateway;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssetVerifierTest {
    private final StorageGateway storage = mock(StorageGateway.class);
    private final AssetVerifier verifier = new AssetVerifier(storage);
    private final UUID videoId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final String prefix = "videos/" + videoId + "/processed/v1/" + jobId + "/attempt-1/";

    @Test
    void acceptsCompleteInventoryAndChecksEverySegment() {
        when(storage.head(anyString())).thenReturn(Optional.of(new StorageGateway.StoredObject(100, "text/plain")));
        when(storage.readText(prefix + "master.m3u8"))
                .thenReturn("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n360p/index.m3u8\n");
        when(storage.readText(prefix + "360p/index.m3u8"))
                .thenReturn("#EXTM3U\n#EXTINF:6.0,\nseg-00000.ts\n#EXT-X-ENDLIST\n");
        assertDoesNotThrow(() -> verifier.verify(event("360p/index.m3u8"), prefix));
    }

    @Test
    void rejectsAssetsOutsideAttemptPrefix() {
        when(storage.head(anyString())).thenReturn(Optional.of(new StorageGateway.StoredObject(100, "text/plain")));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(event("../other/index.m3u8"), prefix));
    }

    private ProcessingResultEvent event(String mediaKey) {
        return new ProcessingResultEvent(UUID.randomUUID(), "VideoProcessingSucceeded", 1, Instant.now(), "test",
                videoId, jobId, 1, 1, UUID.randomUUID(), 60000, List.of(
                new VideoAssetInput("MASTER_PLAYLIST", null, prefix + "master.m3u8", null, null, 100),
                new VideoAssetInput("MEDIA_PLAYLIST", "360p", prefix + mediaKey, prefix + "360p/", 1, 100),
                new VideoAssetInput("THUMBNAIL", null, prefix + "thumbnail.jpg", null, null, 100)), null, null);
    }
}
