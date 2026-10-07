package com.videoservice.upload;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface StorageGateway {
    boolean available();

    String initiate(String sourceKey, String contentType);

    PartGrant signPart(String sourceKey, String uploadId, int partNumber, long expectedSize, Duration ttl);

    List<RemotePart> listParts(String sourceKey, String uploadId);

    void complete(String sourceKey, String uploadId, List<CompletePart> parts);

    Optional<StoredObject> head(String sourceKey);

    void abort(String sourceKey, String uploadId);

    List<String> findMultipartUploadIds(String sourceKey);

    void deletePrefix(String prefix);

    void abortPrefix(String prefix);

    boolean prefixEmpty(String prefix);

    StorageAccess scopedAccess(String sourceKey, String outputPrefix, Instant absoluteDeadline);

    String readText(String objectKey);

    String signRead(String objectKey, Duration ttl);

    record PartGrant(String url, String method, Map<String, String> requiredHeaders,
                     Instant expiresAt, long expectedSizeBytes) {
    }

    record RemotePart(int partNumber, String etag, long sizeBytes) {
    }

    record StoredObject(long sizeBytes, String contentType) {
    }

    record StorageAccess(String endpoint, String bucket, String region, String accessKeyId,
                         String secretAccessKey, String sessionToken, Instant expiresAt) {
    }
}
