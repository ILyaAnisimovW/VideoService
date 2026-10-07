package com.videoservice.processing;

import com.videoservice.catalog.VideoCatalog;
import com.videoservice.catalog.VideoView;
import com.videoservice.catalog.Visibility;
import com.videoservice.infrastructure.IdempotencyStore;
import com.videoservice.shared.exception.ApiException;
import com.videoservice.upload.StorageGateway;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProcessingCoordinatorTest {
    private final ProcessingRepository jobs = mock(ProcessingRepository.class);
    private final VideoCatalog catalog = mock(VideoCatalog.class);
    private final StorageGateway storage = mock(StorageGateway.class);
    private final IdempotencyStore idempotency = mock(IdempotencyStore.class);
    private final AssetVerifier verifier = mock(AssetVerifier.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final ProcessingCoordinator coordinator;

    ProcessingCoordinatorTest() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        coordinator = new ProcessingCoordinator(jobs, catalog, storage, idempotency, verifier, manager);
    }

    @Test
    void staleResultAfterDeletionCannotPublish() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        UUID leaseId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ProcessingJobData job = job(jobId, videoId, leaseId, "RUNNING");
        when(jobs.data(jobId, false)).thenReturn(Optional.of(job));
        when(jobs.data(jobId, true)).thenReturn(Optional.of(job));
        when(jobs.eventDisposition(eventId)).thenReturn(Optional.empty());
        when(catalog.lockForMaintenance(videoId)).thenReturn(video(videoId, "DELETING"));
        ProcessingResultEvent result = new ProcessingResultEvent(eventId, "VideoProcessingFailed", 1, Instant.now(),
                "trace", videoId, jobId, 1, 1, leaseId, null, null, "TRANSCODE_FAILED", false);

        assertEquals("STALE", coordinator.applyResult(result));
        verify(jobs).recordEvent(eventId, "STALE");
        verify(catalog, never()).markReady(any(), anyInt());
        verify(catalog, never()).markFailed(any());
    }

    @Test
    void secondClaimCannotTakeLiveLease() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();
        ProcessingJobData job = job(jobId, videoId, UUID.randomUUID(), "RUNNING");
        when(jobs.data(jobId, false)).thenReturn(Optional.of(job));
        when(jobs.data(jobId, true)).thenReturn(Optional.of(job));
        when(catalog.lockForMaintenance(videoId)).thenReturn(video(videoId, "ACTIVE"));

        ApiException failure = assertThrows(ApiException.class, () -> coordinator.claim(jobId, 1, "other-worker"));
        assertEquals("JOB_BUSY", failure.getCode());
        verifyNoInteractions(storage);
    }

    private ProcessingJobData job(UUID id, UUID videoId, UUID leaseId, String state) {
        return new ProcessingJobData(id, videoId, 1, state, 1, 3, "worker-1", leaseId,
                Instant.now().plusSeconds(60), Instant.now(), "videos/" + videoId + "/source/input.mp4", Instant.now());
    }

    private VideoView video(UUID id, String lifecycle) {
        return new VideoView(id, UUID.randomUUID(), "title", null, Visibility.PRIVATE,
                "PROCESSING", "CLEAR", lifecycle, 1, 1, null, Instant.now(), Instant.now());
    }
}
