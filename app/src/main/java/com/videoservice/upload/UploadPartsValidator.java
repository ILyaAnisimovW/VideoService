package com.videoservice.upload;

import com.videoservice.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class UploadPartsValidator {
    private UploadPartsValidator() {
    }

    static void validateShape(UploadSessionData session, List<CompletePart> parts) {
        if (parts == null || parts.size() != session.partCount()) {
            throw invalidParts();
        }
        for (int index = 0; index < parts.size(); index++) {
            CompletePart part = parts.get(index);
            if (part == null || part.partNumber() != index + 1 || part.etag() == null ||
                    part.etag().isBlank() || part.etag().length() > 256) {
                throw invalidParts();
            }
        }
    }

    static void validateRemote(UploadSessionData session, List<CompletePart> declared,
                               List<StorageGateway.RemotePart> uploaded) {
        List<StorageGateway.RemotePart> remote = new ArrayList<>(uploaded);
        remote.sort(Comparator.comparingInt(StorageGateway.RemotePart::partNumber));
        if (remote.size() != declared.size()) {
            throw invalidParts();
        }
        for (int index = 0; index < remote.size(); index++) {
            var actual = remote.get(index);
            var part = declared.get(index);
            if (actual.partNumber() != part.partNumber() || !actual.etag().equals(part.etag()) ||
                    actual.sizeBytes() != session.expectedPartSize(part.partNumber())) {
                throw invalidParts();
            }
        }
    }

    private static ApiException invalidParts() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PARTS", "Неверный список загруженных частей");
    }
}
