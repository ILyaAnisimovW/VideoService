package com.videoservice.deletion;

import com.videoservice.catalog.VideoCatalog;
import com.videoservice.catalog.VideoView;
import com.videoservice.processing.ProcessingCoordinator;
import com.videoservice.playback.PlaybackService;
import com.videoservice.shared.exception.ApiException;
import com.videoservice.upload.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeletionService {
    private final DeletionRepository tasks;
    private final VideoCatalog catalog;
    private final ProcessingCoordinator processing;
    private final UploadService uploads;
    private final PlaybackService playback;

    @Transactional
    public DeletionTaskView request(UUID actorId, UUID videoId) {
        VideoView video = catalog.lockForMaintenance(videoId);
        catalog.requireOwned(actorId, videoId);
        if (video.lifecycleStatus().equals("DELETED")) return null;
        if (video.lifecycleStatus().equals("DELETING")) {
            return tasks.find(videoId).orElseThrow(this::hidden);
        }
        catalog.beginDeletion(videoId);
        processing.cancel(videoId);
        uploads.cancelForVideo(videoId);
        playback.revoke(videoId);
        return tasks.create(videoId);
    }

    @Transactional(readOnly = true)
    public DeletionTaskView get(UUID actorId, UUID videoId) {
        catalog.requireOwned(actorId, videoId);
        return tasks.find(videoId).orElseThrow(this::hidden);
    }

    private ApiException hidden() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Задача удаления не найдена");
    }
}
