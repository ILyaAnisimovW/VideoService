package com.videoservice.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.videoservice.auth.entity.UserRole;
import com.videoservice.auth.service.AuthService;
import com.videoservice.infrastructure.IdempotencyStore;
import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VideoCatalog {
    private final VideoRepository videos;
    private final AuthService identity;
    private final IdempotencyStore idempotency;

    @Transactional
    public VideoView create(UUID actorId, String key, CreateVideoRequest request) {
        identity.currentUser(actorId);
        idempotency.validateKey(key);
        String hash = idempotency.fingerprint("POST", "/api/v1/videos", request);
        if (!idempotency.reserve(actorId, "createVideo", key, hash)) {
            return idempotency.replay(actorId, "createVideo", key, hash, VideoView.class)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Запрос ещё выполняется"));
        }
        VideoView created = videos.create(actorId, request);
        idempotency.complete(actorId, "createVideo", key, hash, 201, created,
                "/api/v1/videos/" + created.id(), '"' + Long.toString(created.rowVersion()) + '"');
        return created;
    }

    @Transactional(readOnly = true)
    public VideoView getVisible(UUID actorId, UUID id) {
        VideoView video = videos.find(id).orElseThrow(this::hidden);
        if (actorId != null && video.ownerId().equals(actorId)) {
            identity.currentUser(actorId);
            return video;
        }
        if (actorId != null && privileged(actorId)) {
            return video;
        }
        if (!video.lifecycleStatus().equals("ACTIVE") || !video.moderationStatus().equals("CLEAR") ||
                !video.processingStatus().equals("READY") || video.visibility() == Visibility.PRIVATE) {
            throw hidden();
        }
        return video;
    }

    @Transactional(readOnly = true)
    public VideoPage listPublic(int limit, String cursor) {
        return page(false, null, limit, cursor);
    }

    @Transactional(readOnly = true)
    public VideoPage listOwned(UUID actorId, int limit, String cursor) {
        identity.currentUser(actorId);
        return page(true, actorId, limit, cursor);
    }

    private VideoPage page(boolean owned, UUID actorId, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "limit должен быть от 1 до 100");
        }
        Instant beforeTime = null;
        UUID beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 2048) throw new IllegalArgumentException();
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] values = decoded.split("\\|", -1);
                if (values.length != 2) throw new IllegalArgumentException();
                beforeTime = Instant.parse(values[0]);
                beforeId = UUID.fromString(values[1]);
            } catch (RuntimeException ex) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Неверный cursor");
            }
        }
        List<VideoView> rows = videos.list(owned, actorId, beforeTime, beforeId, limit + 1);
        boolean more = rows.size() > limit;
        List<VideoView> items = more ? rows.subList(0, limit) : rows;
        String next = null;
        if (more) {
            VideoView last = items.get(items.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return new VideoPage(items, next);
    }

    @Transactional
    public VideoView patch(UUID actorId, UUID id, String ifMatch, JsonNode body) {
        identity.currentUser(actorId);
        VideoView video = videos.lock(id).orElseThrow(this::hidden);
        if (!video.ownerId().equals(actorId)) throw hidden();
        requireActive(video);
        checkVersion(video, ifMatch);
        if (body == null || !body.isObject() || body.isEmpty()) invalidPatch();
        String title = video.title();
        String description = video.description();
        Visibility visibility = video.visibility();
        var fields = body.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode value = body.get(field);
            switch (field) {
                case "title" -> {
                    if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 120) invalidPatch();
                    title = value.asText().trim();
                }
                case "description" -> {
                    if (!value.isNull() && (!value.isTextual() || value.asText().length() > 5000)) invalidPatch();
                    description = value.isNull() ? null : value.asText();
                }
                case "visibility" -> {
                    if (!value.isTextual()) invalidPatch();
                    try { visibility = Visibility.valueOf(value.asText()); }
                    catch (IllegalArgumentException ex) { invalidPatch(); }
                }
                default -> invalidPatch();
            }
        }
        videos.patch(id, title, description, visibility);
        return videos.find(id).orElseThrow(this::hidden);
    }

    @Transactional
    public VideoView moderate(UUID actorId, UUID id, String ifMatch, ModerationRequest request) {
        if (!privileged(actorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Нужна роль модератора");
        }
        VideoView video = videos.lock(id).orElseThrow(this::hidden);
        requireActive(video);
        checkVersion(video, ifMatch);
        videos.moderate(id, request.status(), request.reason().trim());
        return videos.find(id).orElseThrow(this::hidden);
    }

    @Transactional(readOnly = true)
    public VideoView requirePlayable(UUID viewerId, UUID id) {
        VideoView video = videos.find(id).orElseThrow(this::hidden);
        if (video.lifecycleStatus().equals("DELETED") && viewerId != null && viewerId.equals(video.ownerId())) {
            throw new ApiException(HttpStatus.GONE, "VIDEO_DELETED", "Видео удалено");
        }
        if (!video.lifecycleStatus().equals("ACTIVE") || video.moderationStatus().equals("BLOCKED")) {
            if (viewerId != null && viewerId.equals(video.ownerId())) {
                throw new ApiException(HttpStatus.CONFLICT, "VIDEO_BLOCKED", "Видео недоступно");
            }
            throw hidden();
        }
        if (!video.processingStatus().equals("READY")) {
            if (viewerId != null && viewerId.equals(video.ownerId())) {
                throw new ApiException(HttpStatus.CONFLICT, "VIDEO_NOT_READY", "Видео ещё не готово");
            }
            throw hidden();
        }
        if (video.visibility() == Visibility.PRIVATE) {
            if (viewerId == null || !viewerId.equals(video.ownerId())) throw hidden();
            identity.currentUser(viewerId);
        }
        return video;
    }

    public void markProcessing(UUID id) { videos.markProcessing(id); }
    public void markReady(UUID id, int durationMs) { videos.markReady(id, durationMs); }
    public void markFailed(UUID id) { videos.markFailed(id); }
    public void markRequeued(UUID id) { videos.markRequeued(id); }
    public void incrementProcessingVersion(UUID id) { videos.incrementProcessingVersion(id); }

    public void publishAssets(UUID videoId, UUID jobId, int version, int attemptNo,
                              List<VideoAssetInput> assets, int durationMs) {
        for (VideoAssetInput asset : assets) {
            videos.insertProcessedAsset(videoId, jobId, version, attemptNo, asset);
        }
        videos.markReady(videoId, durationMs);
    }

    @Transactional(readOnly = true)
    public List<VideoAssetView> acceptedAssets(UUID videoId, int version) {
        return videos.acceptedAssets(videoId, version);
    }

    private boolean privileged(UUID actorId) {
        UserRole role = identity.currentUser(actorId).role();
        return role == UserRole.MODERATOR || role == UserRole.ADMIN;
    }

    private void checkVersion(VideoView video, String ifMatch) {
        if (ifMatch == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", "Требуется If-Match");
        if (!ifMatch.equals("\"" + video.rowVersion() + "\"")) {
            throw new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", "Видео изменилось");
        }
    }

    private void requireActive(VideoView video) {
        if (video.lifecycleStatus().equals("DELETED")) {
            throw new ApiException(HttpStatus.GONE, "VIDEO_DELETED", "Видео удалено");
        }
        if (!video.lifecycleStatus().equals("ACTIVE")) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Видео удаляется");
        }
    }

    private void invalidPatch() {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Неверные поля видео");
    }

    /** Acquires the Video lock first; upload and result transactions use this order. */
    public VideoView requireOwnedForUpload(UUID actorId, UUID id) {
        identity.currentUser(actorId);
        VideoView video = videos.lock(id).orElseThrow(this::hidden);
        if (!video.ownerId().equals(actorId)) {
            throw hidden();
        }
        if (!video.lifecycleStatus().equals("ACTIVE") || !video.processingStatus().equals("WAITING_UPLOAD")) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Видео недоступно для загрузки");
        }
        return video;
    }

    public VideoView requireOwned(UUID actorId, UUID id) {
        identity.currentUser(actorId);
        VideoView video = videos.find(id).orElseThrow(this::hidden);
        if (!video.ownerId().equals(actorId)) {
            throw hidden();
        }
        return video;
    }

    public void acceptSource(VideoView video, String sourceKey, long sizeBytes) {
        videos.insertSource(video.id(), video.processingVersion(), sourceKey, sizeBytes);
        videos.markQueued(video.id());
    }

    public void beginDeletion(UUID videoId) {
        videos.markDeleting(videoId);
    }

    public VideoView lockForMaintenance(UUID videoId) {
        return videos.lock(videoId).orElseThrow(this::hidden);
    }

    public void finishDeletion(UUID videoId) {
        videos.markDeleted(videoId);
    }

    private ApiException hidden() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Видео не найдено");
    }
}
