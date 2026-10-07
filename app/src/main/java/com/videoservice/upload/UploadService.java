package com.videoservice.upload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoservice.catalog.VideoCatalog;
import com.videoservice.catalog.VideoView;
import com.videoservice.deletion.DeletionTasks;
import com.videoservice.infrastructure.IdempotencyStore;
import com.videoservice.processing.ProcessingCoordinator;
import com.videoservice.processing.ProcessingJobView;
import com.videoservice.shared.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class UploadService {
    private static final long PART_SIZE = 16L * 1024 * 1024;
    private final UploadRepository uploads;
    private final StorageGateway storage;
    private final VideoCatalog catalog;
    private final ProcessingCoordinator processing;
    private final IdempotencyStore idempotency;
    private final DeletionTasks deletionTasks;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public UploadService(UploadRepository uploads, StorageGateway storage, VideoCatalog catalog,
                         ProcessingCoordinator processing, IdempotencyStore idempotency,
                         DeletionTasks deletionTasks, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.uploads = uploads;
        this.storage = storage;
        this.catalog = catalog;
        this.processing = processing;
        this.idempotency = idempotency;
        this.deletionTasks = deletionTasks;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(manager);
    }

    public UploadSessionView create(UUID actorId, UUID videoId, String key, CreateUploadRequest request) {
        idempotency.validateKey(key);
        String operation = "createUploadSession:" + videoId;
        String hash = idempotency.fingerprint("POST", "/api/v1/videos/" + videoId + "/upload-sessions", request);
        var replay = idempotency.replay(actorId, operation, key, hash, UploadSessionView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        Preparation prepared = Objects.requireNonNull(tx.execute(status -> prepareCreate(actorId, videoId, key, hash, request)));
        if (!prepared.needsRemote()) {
            return view(prepared.session());
        }
        UploadSessionData session = prepared.session();
        List<String> found = storage.findMultipartUploadIds(session.sourceKey());
        String uploadId = found.isEmpty() ? storage.initiate(session.sourceKey(), session.contentType()) : found.get(0);
        for (int i = 1; i < found.size(); i++) {
            storage.abort(session.sourceKey(), found.get(i));
        }
        UploadSessionData opened = Objects.requireNonNull(tx.execute(status -> {
            catalog.lockForMaintenance(videoId);
            UploadSessionData current = uploads.lock(session.id()).orElseThrow(this::hidden);
            if (current.state().equals("INITIATING")) {
                uploads.markOpen(session.id(), uploadId);
            } else if (!current.state().equals("OPEN")) {
                throw invalidState();
            }
            UploadSessionData result = uploads.find(session.id()).orElseThrow(this::hidden);
            UploadSessionView response = new UploadSessionView(result.id(), result.videoId(), result.state(),
                    result.sizeBytes(), result.partSizeBytes(), result.partCount(), result.expiresAt(), List.of(), null);
            idempotency.complete(actorId, operation, key, hash, 201, response,
                    "/api/v1/upload-sessions/" + result.id(), null);
            return result;
        }));
        if (!opened.providerUploadId().equals(uploadId)) {
            storage.abort(session.sourceKey(), uploadId);
        }
        return view(opened);
    }

    public UploadSessionView get(UUID actorId, UUID sessionId) {
        UploadSessionData session = owned(actorId, sessionId);
        if (expired(session)) {
            throw expiredError();
        }
        return view(session);
    }

    public StorageGateway.PartGrant signPart(UUID actorId, UUID sessionId, int partNumber) {
        UploadSessionData session = owned(actorId, sessionId);
        if (expired(session)) {
            throw expiredError();
        }
        if (!session.state().equals("OPEN")) {
            throw invalidState();
        }
        if (partNumber < 1 || partNumber > session.partCount()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARTS", "Неверный номер части");
        }
        Duration ttl = Duration.between(Instant.now(), session.expiresAt());
        if (ttl.compareTo(Duration.ofMinutes(10)) > 0) {
            ttl = Duration.ofMinutes(10);
        }
        if (ttl.isNegative() || ttl.isZero()) {
            throw expiredError();
        }
        return storage.signPart(session.sourceKey(), session.providerUploadId(), partNumber,
                session.expectedPartSize(partNumber), ttl);
    }

    public ProcessingJobView complete(UUID actorId, UUID sessionId, String key,
                                      CompleteUploadRequest request, String traceId) {
        idempotency.validateKey(key);
        String operation = "completeUpload:" + sessionId;
        String hash = idempotency.fingerprint("POST", "/api/v1/upload-sessions/" + sessionId + "/complete", request);
        var replay = idempotency.replay(actorId, operation, key, hash, ProcessingJobView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        UploadSessionData first = owned(actorId, sessionId);
        if (first.state().equals("COMPLETED")) {
            return processing.get(first.processingJobId());
        }
        UploadPartsValidator.validateShape(first, request.parts());
        UploadSessionData current = Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(first.videoId());
            catalog.requireOwned(actorId, first.videoId());
            UploadSessionData locked = uploads.lock(sessionId).orElseThrow(this::hidden);
            if (locked.state().equals("COMPLETED")) {
                return locked;
            }
            ensureUploadable(video);
            if (expired(locked) && !locked.state().equals("COMPLETING")) {
                throw expiredError();
            }
            if (!locked.state().equals("OPEN") && !locked.state().equals("COMPLETING")) {
                throw invalidState();
            }
            if (locked.state().equals("COMPLETING")) {
                checkCompletingRequest(locked, key, hash);
            }
            return locked;
        }));
        if (current.state().equals("COMPLETED")) {
            return processing.get(current.processingJobId());
        }

        if (current.state().equals("OPEN")) {
            UploadPartsValidator.validateRemote(current, request.parts(),
                    storage.listParts(current.sourceKey(), current.providerUploadId()));
        }
        UploadSessionData completing = Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(current.videoId());
            catalog.requireOwned(actorId, current.videoId());
            UploadSessionData locked = uploads.lock(sessionId).orElseThrow(this::hidden);
            if (locked.state().equals("COMPLETED")) {
                return locked;
            }
            ensureUploadable(video);
            if (locked.state().equals("OPEN")) {
                uploads.markCompleting(sessionId, json(request.parts()), key, hash);
            } else if (locked.state().equals("COMPLETING")) {
                checkCompletingRequest(locked, key, hash);
            } else {
                throw invalidState();
            }
            return uploads.find(sessionId).orElseThrow(this::hidden);
        }));
        if (completing.state().equals("COMPLETED")) {
            return processing.get(completing.processingJobId());
        }

        if (storage.head(completing.sourceKey()).isEmpty()) {
            storage.complete(completing.sourceKey(), completing.providerUploadId(), request.parts());
        }
        StorageGateway.StoredObject source = storage.head(completing.sourceKey())
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                        "Исходный объект пока недоступен"));
        if (source.sizeBytes() != completing.sizeBytes() || !completing.contentType().equals(source.contentType())) {
            tx.executeWithoutResult(status -> {
                catalog.lockForMaintenance(completing.videoId());
                UploadSessionData locked = uploads.lock(sessionId).orElseThrow(this::hidden);
                if (locked.state().equals("COMPLETING")) {
                    stopForCleanup(locked, "ABORTED");
                }
            });
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_MISMATCH", "Размер или тип исходника не совпал");
        }

        return Objects.requireNonNull(tx.execute(status -> {
            VideoView video = catalog.lockForMaintenance(completing.videoId());
            catalog.requireOwned(actorId, completing.videoId());
            UploadSessionData locked = uploads.lock(sessionId).orElseThrow(this::hidden);
            if (locked.state().equals("COMPLETED")) {
                return processing.get(locked.processingJobId());
            }
            ensureUploadable(video);
            if (!locked.state().equals("COMPLETING")) {
                throw invalidState();
            }
            checkCompletingRequest(locked, key, hash);
            catalog.acceptSource(video, locked.sourceKey(), source.sizeBytes());
            ProcessingJobView job = processing.enqueue(video, locked.sourceKey(), traceId);
            uploads.markCompleted(sessionId, job.id());
            idempotency.complete(actorId, operation, key, hash, 202, job,
                    "/api/v1/processing-jobs/" + job.id(), null);
            return job;
        }));
    }

    public void abort(UUID actorId, UUID sessionId) {
        UploadSessionData session = owned(actorId, sessionId);
        if (session.state().equals("ABORTED") || session.state().equals("EXPIRED")) {
            return;
        }
        if (session.state().equals("COMPLETED")) {
            throw invalidState();
        }
        tx.executeWithoutResult(status -> {
            catalog.lockForMaintenance(session.videoId());
            catalog.requireOwned(actorId, session.videoId());
            UploadSessionData locked = uploads.lock(sessionId).orElseThrow(this::hidden);
            if (locked.state().equals("ABORTED") || locked.state().equals("EXPIRED")) {
                return;
            }
            if (locked.state().equals("COMPLETED")) {
                throw invalidState();
            }
            stopForCleanup(locked, "ABORTED");
        });
    }

    /** Called by deletion while the Video row is locked in the same transaction. */
    public void cancelForVideo(UUID videoId) {
        uploads.findActiveForVideo(videoId).ifPresent(session -> uploads.markStopped(session.id(), "ABORTED"));
    }

    @Scheduled(fixedDelayString = "${video.upload.cleanup-poll-ms:60000}")
    public void expireSessions() {
        for (UploadSessionData session : uploads.expired()) {
            tx.executeWithoutResult(status -> {
                catalog.lockForMaintenance(session.videoId());
                UploadSessionData locked = uploads.lock(session.id()).orElseThrow(this::hidden);
                if (locked.state().equals("INITIATING") || locked.state().equals("OPEN") ||
                        locked.state().equals("COMPLETING")) {
                    stopForCleanup(locked, "EXPIRED");
                }
            });
        }
    }

    private Preparation prepareCreate(UUID actorId, UUID videoId, String key, String hash, CreateUploadRequest request) {
        catalog.requireOwnedForUpload(actorId, videoId);
        var active = uploads.findActiveForVideo(videoId);
        if (active.isPresent()) {
            UploadSessionData session = active.get();
            if (expired(session)) {
                throw expiredError();
            }
            if (!session.createKey().equals(key) || !session.createHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_CONFLICT", "Для видео уже есть загрузка");
            }
            if (session.state().equals("OPEN")) {
                return new Preparation(session, false);
            }
            Instant cutoff = Instant.now().minusSeconds(30);
            if (session.updatedAt().isAfter(cutoff)) {
                throw new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Создание загрузки ещё выполняется");
            }
            uploads.claimStaleInitiating(session.id(), cutoff);
            return new Preparation(session, true);
        }
        String extension = switch (request.contentType()) {
            case "video/mp4" -> "mp4";
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Неподдерживаемый тип");
        };
        String sourceKey = "videos/" + videoId + "/source/" + UUID.randomUUID() + "." + extension;
        return new Preparation(uploads.insert(videoId, sourceKey, request.sizeBytes(), request.contentType(), key, hash), true);
    }

    private void ensureUploadable(VideoView video) {
        if (!video.lifecycleStatus().equals("ACTIVE") || !video.processingStatus().equals("WAITING_UPLOAD")) {
            throw invalidState();
        }
    }

    private void checkCompletingRequest(UploadSessionData session, String key, String hash) {
        if (session.completionKey().equals(key) && !session.completionHash().equals(hash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "Ключ уже использован с другим запросом");
        }
        if (!session.completionKey().equals(key)) {
            String code = session.completionHash().equals(hash) ? "REQUEST_IN_PROGRESS" : "REQUEST_CONFLICT";
            throw new ApiException(HttpStatus.CONFLICT, code, "Завершение загрузки уже выполняется");
        }
    }

    private UploadSessionData owned(UUID actorId, UUID sessionId) {
        UploadSessionData session = uploads.find(sessionId).orElseThrow(this::hidden);
        catalog.requireOwned(actorId, session.videoId());
        return session;
    }

    private UploadSessionView view(UploadSessionData session) {
        List<StorageGateway.RemotePart> parts = List.of();
        if (session.state().equals("OPEN") && session.providerUploadId() != null) {
            parts = storage.listParts(session.sourceKey(), session.providerUploadId());
        } else if (session.completionParts() != null) {
            List<CompletePart> saved = savedParts(session.completionParts());
            parts = saved.stream().map(part -> new StorageGateway.RemotePart(part.partNumber(), part.etag(),
                    session.expectedPartSize(part.partNumber()))).toList();
        }
        return new UploadSessionView(session.id(), session.videoId(), session.state(), session.sizeBytes(),
                session.partSizeBytes(), session.partCount(), session.expiresAt(), parts, session.processingJobId());
    }

    private void stopForCleanup(UploadSessionData session, String state) {
        uploads.markStopped(session.id(), state);
        catalog.beginDeletion(session.videoId());
        // Let signed URLs and worker storage capabilities expire before the final sweep.
        deletionTasks.schedule(session.videoId());
    }

    private boolean expired(UploadSessionData session) {
        return (session.state().equals("INITIATING") || session.state().equals("OPEN")) &&
                !session.expiresAt().isAfter(Instant.now());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize completion parts", ex);
        }
    }

    private List<CompletePart> savedParts(String value) {
        try {
            return mapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored completion parts are invalid", ex);
        }
    }

    private ApiException hidden() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Сессия загрузки не найдена");
    }

    private ApiException invalidState() {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Недопустимое состояние загрузки");
    }

    private ApiException expiredError() {
        return new ApiException(HttpStatus.GONE, "UPLOAD_EXPIRED", "Срок загрузки истёк");
    }

    private record Preparation(UploadSessionData session, boolean needsRemote) {
    }
}
