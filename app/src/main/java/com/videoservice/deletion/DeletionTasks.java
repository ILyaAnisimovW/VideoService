package com.videoservice.deletion;

import com.videoservice.catalog.VideoCatalog;
import com.videoservice.upload.StorageGateway;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class DeletionTasks {
    private final DeletionRepository repository;
    private final StorageGateway storage;
    private final VideoCatalog catalog;
    private final TransactionTemplate tx;

    public DeletionTasks(DeletionRepository repository, StorageGateway storage, VideoCatalog catalog,
                         PlatformTransactionManager manager) {
        this.repository = repository;
        this.storage = storage;
        this.catalog = catalog;
        this.tx = new TransactionTemplate(manager);
    }

    /** Schedule while the video row is locked by the caller. */
    public void schedule(UUID videoId) {
        repository.create(videoId);
    }

    @Scheduled(fixedDelayString = "${video.upload.cleanup-poll-ms:60000}")
    public void sweep() {
        for (UUID videoId : repository.claimReady()) {
            try {
                String prefix = "videos/" + videoId + "/";
                storage.abortPrefix(prefix);
                storage.deletePrefix(prefix);
                if (!storage.prefixEmpty(prefix)) {
                    throw new IllegalStateException("Video prefix was not fully deleted");
                }
                tx.executeWithoutResult(status -> {
                    catalog.lockForMaintenance(videoId);
                    catalog.finishDeletion(videoId);
                    repository.complete(videoId);
                });
            } catch (RuntimeException ex) {
                repository.retryOrFail(videoId);
            }
        }
    }
}
