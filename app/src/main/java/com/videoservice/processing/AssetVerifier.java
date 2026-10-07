package com.videoservice.processing;

import com.videoservice.catalog.VideoAssetInput;
import com.videoservice.upload.StorageGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
class AssetVerifier {
    private final StorageGateway storage;

    void verify(ProcessingResultEvent result, String prefix) {
        List<VideoAssetInput> assets = result.assets();
        if (assets == null || assets.size() < 3 || assets.size() > 5 || result.durationMs() == null ||
                result.durationMs() < 1 || result.durationMs() > 1_800_000) invalid();
        Map<String, VideoAssetInput> byKey = new HashMap<>();
        Set<String> qualities = new HashSet<>();
        int masters = 0;
        int thumbnails = 0;
        int renditions = 0;
        for (VideoAssetInput asset : assets) {
            if (asset == null || asset.objectKey() == null || !safeKey(asset.objectKey(), prefix) ||
                    byKey.putIfAbsent(asset.objectKey(), asset) != null || asset.sizeBytes() < 1) invalid();
            if (storage.head(asset.objectKey()).map(object -> object.sizeBytes() == asset.sizeBytes()).orElse(false) == false) invalid();
            switch (asset.kind()) {
                case "MASTER_PLAYLIST" -> {
                    masters++;
                    if (asset.quality() != null || asset.renditionPrefix() != null || asset.segmentCount() != null ||
                            !asset.objectKey().equals(prefix + "master.m3u8")) invalid();
                }
                case "THUMBNAIL" -> {
                    thumbnails++;
                    if (asset.quality() != null || asset.renditionPrefix() != null || asset.segmentCount() != null ||
                            !asset.objectKey().equals(prefix + "thumbnail.jpg")) invalid();
                }
                case "MEDIA_PLAYLIST" -> {
                    renditions++;
                    if (asset.quality() == null || !Set.of("360p", "720p", "1080p", "source").contains(asset.quality()) ||
                            !qualities.add(asset.quality()) || asset.renditionPrefix() == null ||
                            !asset.renditionPrefix().equals(prefix + asset.quality() + "/") ||
                            !asset.objectKey().equals(asset.renditionPrefix() + "index.m3u8") ||
                            asset.segmentCount() == null || asset.segmentCount() < 1) invalid();
                }
                default -> invalid();
            }
        }
        if (masters != 1 || thumbnails != 1 || renditions < 1) invalid();
        String master = storage.readText(prefix + "master.m3u8");
        if (!master.startsWith("#EXTM3U") || master.length() > 1_000_000) invalid();
        Set<String> referenced = new HashSet<>();
        for (String line : master.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) {
                if (value.contains("URI=")) invalid();
                continue;
            }
            if (!safeRelative(value)) invalid();
            String key = prefix + value;
            VideoAssetInput asset = byKey.get(key);
            if (asset == null || !asset.kind().equals("MEDIA_PLAYLIST")) invalid();
            referenced.add(key);
        }
        if (referenced.size() != renditions) invalid();
        for (VideoAssetInput asset : assets) {
            if (!asset.kind().equals("MEDIA_PLAYLIST")) continue;
            String playlist = storage.readText(asset.objectKey());
            if (!playlist.startsWith("#EXTM3U") || playlist.length() > 1_000_000) invalid();
            int segments = 0;
            for (String line : playlist.split("\\R")) {
                String value = line.trim();
                if (value.isEmpty()) continue;
                if (value.startsWith("#")) {
                    if (value.startsWith("#EXT-X-MAP:URI=\"") && value.endsWith("\"")) {
                        checkObject(asset.renditionPrefix(), value.substring(16, value.length() - 1));
                    } else if (value.contains("URI=")) {
                        invalid();
                    }
                    continue;
                }
                checkObject(asset.renditionPrefix(), value);
                segments++;
            }
            if (segments != asset.segmentCount()) invalid();
        }
    }

    private void checkObject(String prefix, String relative) {
        if (!safeRelative(relative) || relative.contains("/")) invalid();
        if (storage.head(prefix + relative).map(object -> object.sizeBytes() > 0).orElse(false) == false) invalid();
    }

    private boolean safeKey(String key, String prefix) {
        return key.startsWith(prefix) && !key.contains("..") && !key.contains("\\") &&
                !key.contains("%") && !key.contains("//") && !key.contains("?") && !key.contains("#");
    }

    private boolean safeRelative(String reference) {
        return !reference.isBlank() && !reference.startsWith("/") && !reference.contains("..") &&
                !reference.contains("\\") && !reference.contains(":") && !reference.contains("%") &&
                !reference.contains("?") && !reference.contains("#") && !reference.contains("//");
    }

    private void invalid() {
        throw new IllegalArgumentException("Invalid processing asset inventory");
    }
}
