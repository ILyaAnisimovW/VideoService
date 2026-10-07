package com.videoservice.playback;

import com.videoservice.auth.service.AuthService;
import com.videoservice.catalog.VideoAssetView;
import com.videoservice.catalog.VideoCatalog;
import com.videoservice.catalog.VideoView;
import com.videoservice.shared.exception.ApiException;
import com.videoservice.upload.StorageGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaybackService {
    private final PlaybackRepository sessions;
    private final VideoCatalog catalog;
    private final StorageGateway storage;
    private final AuthService identity;
    private final SecureRandom random = new SecureRandom();

    @Value("${video.playback.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    public PlaybackSessionView create(UUID viewerId, UUID videoId) {
        if (viewerId != null) identity.currentUser(viewerId);
        VideoView video = catalog.requirePlayable(viewerId, videoId);
        List<VideoAssetView> assets = catalog.acceptedAssets(videoId, video.processingVersion());
        VideoAssetView master = assets.stream().filter(asset -> asset.kind().equals("MASTER_PLAYLIST"))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "VIDEO_NOT_READY", "Нет плейлиста"));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(15));
        sessions.create(id, videoId, viewerId, hash(token), video.processingVersion(), expiresAt);
        return new PlaybackSessionView(id, videoId, manifestUrl(id, master.id(), token), expiresAt);
    }

    public String render(UUID sessionId, UUID assetId, String token) {
        PlaybackSessionData session = sessions.find(sessionId).orElseThrow(this::hidden);
        if (token == null || token.length() > 128 || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                session.tokenHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_PLAYBACK_TOKEN", "Неверный токен просмотра");
        }
        Instant now = Instant.now();
        if (session.revokedAt() != null || !session.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.GONE, "PLAYBACK_EXPIRED", "Сессия просмотра истекла");
        }
        if (session.viewerId() != null) identity.currentUser(session.viewerId());
        VideoView video = catalog.requirePlayable(session.viewerId(), session.videoId());
        if (video.processingVersion() != session.processingVersion()) throw hidden();
        List<VideoAssetView> assets = catalog.acceptedAssets(video.id(), video.processingVersion());
        VideoAssetView asset = assets.stream().filter(item -> item.id().equals(assetId) &&
                (item.kind().equals("MASTER_PLAYLIST") || item.kind().equals("MEDIA_PLAYLIST")))
                .findFirst().orElseThrow(this::hidden);
        Duration remaining = Duration.between(now, session.expiresAt());
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ApiException(HttpStatus.GONE, "PLAYBACK_EXPIRED", "Сессия просмотра истекла");
        }
        Duration ttl = remaining.compareTo(Duration.ofMinutes(5)) < 0 ? remaining : Duration.ofMinutes(5);
        String source = storage.readText(asset.objectKey());
        String output = asset.kind().equals("MASTER_PLAYLIST")
                ? HlsRewriter.master(source, asset, assets, rendition -> manifestUrl(session.id(), rendition.id(), token))
                : HlsRewriter.media(source, asset, storage, ttl);
        VideoView after = catalog.requirePlayable(session.viewerId(), session.videoId());
        if (after.processingVersion() != session.processingVersion()) throw hidden();
        return output;
    }

    public void revoke(UUID videoId) {
        sessions.revoke(videoId);
    }

    private String manifestUrl(UUID sessionId, UUID assetId, String token) {
        return publicBaseUrl.replaceAll("/+$", "") + "/api/v1/playback-sessions/" + sessionId +
                "/manifests/" + assetId + "?token=" + token;
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ApiException hidden() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Плейлист не найден");
    }
}
