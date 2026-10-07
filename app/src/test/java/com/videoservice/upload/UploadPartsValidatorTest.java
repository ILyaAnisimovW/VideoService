package com.videoservice.upload;

import com.videoservice.shared.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadPartsValidatorTest {
    private static final long PART_SIZE = 16L * 1024 * 1024;

    @Test
    void requiresEveryPartInOrderAndExactRemoteSizes() {
        UploadSessionData session = session(PART_SIZE + 7);
        List<CompletePart> declared = List.of(new CompletePart(1, "etag-1"), new CompletePart(2, "etag-2"));

        UploadPartsValidator.validateShape(session, declared);
        UploadPartsValidator.validateRemote(session, declared, List.of(
                new StorageGateway.RemotePart(2, "etag-2", 7),
                new StorageGateway.RemotePart(1, "etag-1", PART_SIZE)));

        assertEquals(2, session.partCount());
        assertEquals(7, session.expectedPartSize(2));
    }

    @Test
    void rejectsMissingDuplicateAndWrongSizedParts() {
        UploadSessionData session = session(PART_SIZE + 7);
        assertThrows(ApiException.class, () -> UploadPartsValidator.validateShape(session,
                List.of(new CompletePart(1, "etag-1"))));
        assertThrows(ApiException.class, () -> UploadPartsValidator.validateShape(session,
                List.of(new CompletePart(1, "etag-1"), new CompletePart(1, "etag-2"))));

        List<CompletePart> declared = List.of(new CompletePart(1, "etag-1"), new CompletePart(2, "etag-2"));
        assertThrows(ApiException.class, () -> UploadPartsValidator.validateRemote(session, declared, List.of(
                new StorageGateway.RemotePart(1, "etag-1", PART_SIZE),
                new StorageGateway.RemotePart(2, "etag-2", 8))));
        assertThrows(ApiException.class, () -> UploadPartsValidator.validateRemote(session, declared, List.of(
                new StorageGateway.RemotePart(1, "etag-1", PART_SIZE),
                new StorageGateway.RemotePart(2, "wrong-etag", 7))));
    }

    private UploadSessionData session(long size) {
        return new UploadSessionData(UUID.randomUUID(), UUID.randomUUID(), "OPEN", "videos/test/source/file.mp4",
                "upload-id", size, PART_SIZE, "video/mp4", null, null,
                "create-key", "hash", null, null, Instant.now().plusSeconds(3600), Instant.now());
    }
}
