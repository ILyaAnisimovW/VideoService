package com.videoservice.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
class MediaProcessor {
    private final ObjectMapper mapper;
    private final WorkerStorage storage;

    record Output(int durationMs, List<WorkerMessages.Asset> assets) { }
    private record Probe(int width, int height, int durationMs) { }

    Output process(WorkerMessages.Lease lease, LeaseGuard guard) {
        Path root;
        try { root = Files.createTempDirectory("video-worker-"); }
        catch (IOException ex) { throw new MediaFailure("WORKER_IO_ERROR", true); }
        try (S3Client s3 = storage.connect(lease.storageAccess())) {
            String extension = lease.sourceKey().substring(lease.sourceKey().lastIndexOf('.') + 1);
            if (!List.of("mp4", "mov", "webm").contains(extension)) throw new MediaFailure("UNSUPPORTED_MEDIA", false);
            Path source = root.resolve("source." + extension);
            storage.download(s3, lease, source);
            ensureActive(guard);
            Probe probe = probe(source, guard);
            List<MediaPlanner.Rendition> plan = MediaPlanner.plan(probe.width(), probe.height());
            List<WorkerMessages.Asset> assets = new ArrayList<>();
            StringBuilder master = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n");
            for (MediaPlanner.Rendition rendition : plan) {
                ensureActive(guard);
                Path directory = root.resolve(rendition.quality());
                Files.createDirectories(directory);
                Path playlist = directory.resolve("index.m3u8");
                run(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                        "-i", source.toString(), "-map", "0:v:0", "-map", "0:a:0?",
                        "-vf", "scale=" + rendition.width() + ":" + rendition.height(),
                        "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "128k", "-f", "hls", "-hls_time", "6",
                        "-hls_playlist_type", "vod", "-hls_flags", "independent_segments",
                        "-hls_segment_filename", directory.resolve("seg-%05d.ts").toString(),
                        playlist.toString()), guard);
                long count;
                try (var files = Files.list(directory)) {
                    count = files.filter(path -> path.getFileName().toString().endsWith(".ts")).count();
                }
                if (count < 1 || count > Integer.MAX_VALUE || !Files.exists(playlist)) {
                    throw new MediaFailure("TRANSCODE_FAILED", false);
                }
                int bandwidth = switch (rendition.quality()) {
                    case "360p" -> 900_000;
                    case "720p" -> 2_800_000;
                    case "1080p" -> 5_000_000;
                    default -> 700_000;
                };
                master.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append(",RESOLUTION=")
                        .append(rendition.width()).append('x').append(rendition.height()).append('\n')
                        .append(rendition.quality()).append("/index.m3u8\n");
                String prefix = lease.outputPrefix() + rendition.quality() + "/";
                try (var files = Files.list(directory)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        String name = file.getFileName().toString();
                        storage.upload(s3, lease, prefix + name, file,
                                name.endsWith(".m3u8") ? "application/vnd.apple.mpegurl" : "video/mp2t");
                    }
                }
                assets.add(new WorkerMessages.Asset("MEDIA_PLAYLIST", rendition.quality(),
                        prefix + "index.m3u8", prefix, (int) count, Files.size(playlist)));
            }
            ensureActive(guard);
            Path thumbnail = root.resolve("thumbnail.jpg");
            run(List.of("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-ss", probe.durationMs() > 2000 ? "1" : "0", "-i", source.toString(),
                    "-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "3", thumbnail.toString()), guard);
            storage.upload(s3, lease, lease.outputPrefix() + "thumbnail.jpg", thumbnail, "image/jpeg");
            assets.add(new WorkerMessages.Asset("THUMBNAIL", null, lease.outputPrefix() + "thumbnail.jpg",
                    null, null, Files.size(thumbnail)));
            Path masterFile = root.resolve("master.m3u8");
            Files.writeString(masterFile, master.toString(), StandardCharsets.UTF_8);
            storage.upload(s3, lease, lease.outputPrefix() + "master.m3u8", masterFile,
                    "application/vnd.apple.mpegurl");
            assets.add(new WorkerMessages.Asset("MASTER_PLAYLIST", null, lease.outputPrefix() + "master.m3u8",
                    null, null, Files.size(masterFile)));
            return new Output(probe.durationMs(), assets);
        } catch (MediaFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw new MediaFailure("DEPENDENCY_UNAVAILABLE", true);
        } finally {
            deleteTemp(root);
        }
    }

    private Probe probe(Path source, LeaseGuard guard) throws IOException, InterruptedException {
        String output = capture(List.of("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "format=duration:stream=width,height", "-of", "json",
                source.toString()), guard);
        try {
            JsonNode json = mapper.readTree(output);
            JsonNode video = json.path("streams").path(0);
            int width = video.path("width").asInt();
            int height = video.path("height").asInt();
            double duration = Double.parseDouble(json.path("format").path("duration").asText());
            if (width < 2 || height < 2 || !Double.isFinite(duration) || duration <= 0) {
                throw new MediaFailure("UNSUPPORTED_MEDIA", false);
            }
            if (duration > 1800) throw new MediaFailure("DURATION_LIMIT", false);
            return new Probe(width, height, Math.max(1, (int) Math.round(duration * 1000)));
        } catch (RuntimeException ex) {
            if (ex instanceof MediaFailure media) throw media;
            throw new MediaFailure("UNSUPPORTED_MEDIA", false);
        }
    }

    private String capture(List<String> command, LeaseGuard guard) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new MediaFailure("PROBE_TIMEOUT", true);
        }
        ensureActive(guard);
        if (process.exitValue() != 0) throw new MediaFailure("UNSUPPORTED_MEDIA", false);
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private void run(List<String> command, LeaseGuard guard) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        while (!process.waitFor(1, TimeUnit.SECONDS)) {
            if (!guard.active()) {
                process.destroyForcibly();
                throw new MediaFailure("LEASE_LOST", true);
            }
        }
        ensureActive(guard);
        if (process.exitValue() != 0) throw new MediaFailure("TRANSCODE_FAILED", false);
    }

    private void ensureActive(LeaseGuard guard) {
        if (!guard.active()) throw new MediaFailure("LEASE_LOST", true);
    }

    private void deleteTemp(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // OS temp cleanup remains a fallback; no source key or credentials are logged.
        }
    }
}
