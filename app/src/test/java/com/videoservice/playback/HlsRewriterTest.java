package com.videoservice.playback;

import com.videoservice.catalog.VideoAssetView;
import com.videoservice.upload.StorageGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HlsRewriterTest {
    private final UUID videoId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final String prefix = "videos/" + videoId + "/processed/v1/" + jobId + "/attempt-1/";

    @Test
    void masterUsesOnlyRegisteredRenditions() {
        VideoAssetView master = asset("MASTER_PLAYLIST", null, prefix + "master.m3u8", null, null);
        VideoAssetView media = asset("MEDIA_PLAYLIST", "360p", prefix + "360p/index.m3u8", prefix + "360p/", 1);
        String rendered = HlsRewriter.master("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n360p/index.m3u8\n",
                master, List.of(master, media), item -> "/manifest/" + item.id());
        assertTrue(rendered.contains("/manifest/" + media.id()));
        assertThrows(RuntimeException.class, () -> HlsRewriter.master(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n../private/index.m3u8\n",
                master, List.of(master, media), item -> "/manifest/" + item.id()));
    }

    @Test
    void mediaSignsOnlySameRenditionSegments() {
        StorageGateway storage = mock(StorageGateway.class);
        VideoAssetView media = asset("MEDIA_PLAYLIST", "360p", prefix + "360p/index.m3u8", prefix + "360p/", 1);
        when(storage.head(prefix + "360p/seg-00000.ts"))
                .thenReturn(Optional.of(new StorageGateway.StoredObject(100, "video/mp2t")));
        when(storage.signRead(prefix + "360p/seg-00000.ts", Duration.ofMinutes(5)))
                .thenReturn("https://signed.example/segment");
        String rendered = HlsRewriter.media("#EXTM3U\n#EXTINF:6.0,\nseg-00000.ts\n#EXT-X-ENDLIST\n",
                media, storage, Duration.ofMinutes(5));
        assertTrue(rendered.contains("https://signed.example/segment"));
        assertThrows(RuntimeException.class, () -> HlsRewriter.media(
                "#EXTM3U\n#EXTINF:6.0,\nhttps://other.example/file.ts\n", media, storage, Duration.ofMinutes(5)));
        assertThrows(RuntimeException.class, () -> HlsRewriter.media(
                "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\nseg-00000.ts\n",
                media, storage, Duration.ofMinutes(5)));
    }

    private VideoAssetView asset(String kind, String quality, String key, String renditionPrefix, Integer segments) {
        return new VideoAssetView(UUID.randomUUID(), videoId, jobId, 1, 1, kind, quality, key,
                renditionPrefix, segments, 100);
    }
}
