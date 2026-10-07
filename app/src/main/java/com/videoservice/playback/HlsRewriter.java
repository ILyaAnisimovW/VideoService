package com.videoservice.playback;

import com.videoservice.catalog.VideoAssetView;
import com.videoservice.upload.StorageGateway;
import com.videoservice.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

final class HlsRewriter {
    private HlsRewriter() {
    }

    static String master(String playlist, VideoAssetView master, List<VideoAssetView> assets,
                         Function<VideoAssetView, String> manifestUrl) {
        requirePlaylist(playlist);
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (String line : playlist.split("\\R", -1)) {
            String value = line.trim();
            if (value.isEmpty()) { output.append('\n'); continue; }
            if (value.startsWith("#")) {
                if (value.contains("URI=")) invalid();
                output.append(line).append('\n');
                continue;
            }
            if (!safeRelative(value)) invalid();
            String key = parent(master.objectKey()) + value;
            VideoAssetView rendition = assets.stream().filter(a -> a.kind().equals("MEDIA_PLAYLIST") &&
                    a.objectKey().equals(key)).findFirst().orElseThrow(HlsRewriter::invalidException);
            output.append(manifestUrl.apply(rendition)).append('\n');
            count++;
        }
        if (count < 1) invalid();
        return output.toString();
    }

    static String media(String playlist, VideoAssetView asset, StorageGateway storage, Duration ttl) {
        requirePlaylist(playlist);
        if (asset.renditionPrefix() == null) invalid();
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (String line : playlist.split("\\R", -1)) {
            String value = line.trim();
            if (value.startsWith("#EXT-X-MAP:URI=\"") && value.endsWith("\"")) {
                String relative = value.substring(16, value.length() - 1);
                output.append("#EXT-X-MAP:URI=\"")
                        .append(segmentUrl(asset, relative, storage, ttl)).append("\"\n");
            } else if (value.startsWith("#")) {
                if (value.contains("URI=")) invalid();
                output.append(line).append('\n');
            } else if (value.isEmpty()) {
                output.append('\n');
            } else {
                output.append(segmentUrl(asset, value, storage, ttl)).append('\n');
                count++;
            }
        }
        if (asset.segmentCount() == null || count != asset.segmentCount()) invalid();
        return output.toString();
    }

    private static String segmentUrl(VideoAssetView asset, String relative, StorageGateway storage, Duration ttl) {
        if (!safeRelative(relative) || relative.contains("/")) invalid();
        String key = asset.renditionPrefix() + relative;
        if (storage.head(key).map(object -> object.sizeBytes() > 0).orElse(false) == false) invalid();
        return storage.signRead(key, ttl);
    }

    private static String parent(String key) {
        return key.substring(0, key.lastIndexOf('/') + 1);
    }

    private static boolean safeRelative(String value) {
        return !value.isBlank() && !value.startsWith("/") && !value.contains("..") &&
                !value.contains("\\") && !value.contains(":") && !value.contains("%") &&
                !value.contains("?") && !value.contains("#") && !value.contains("//");
    }

    private static void requirePlaylist(String playlist) {
        if (playlist == null || !playlist.startsWith("#EXTM3U") || playlist.length() > 1_000_000) invalid();
    }

    private static void invalid() { throw invalidException(); }

    private static ApiException invalidException() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_ASSET", "Плейлист недоступен");
    }
}
