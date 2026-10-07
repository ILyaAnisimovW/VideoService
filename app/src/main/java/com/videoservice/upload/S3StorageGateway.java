package com.videoservice.upload;

import com.videoservice.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class S3StorageGateway implements StorageGateway {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StsClient sts;
    @Value("${video.storage.bucket}")
    private String bucket;
    @Value("${video.storage.endpoint}")
    private String endpoint;
    @Value("${video.storage.region}")
    private String region;

    @Override
    public boolean available() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (SdkException ex) {
            return false;
        }
    }

    @Override
    public String initiate(String sourceKey, String contentType) {
        try {
            return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                    .bucket(bucket).key(sourceKey).contentType(contentType).build()).uploadId();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public PartGrant signPart(String sourceKey, String uploadId, int partNumber, long expectedSize, Duration ttl) {
        try {
            Instant signedAt = Instant.now();
            var signed = presigner.presignUploadPart(UploadPartPresignRequest.builder()
                    .signatureDuration(ttl)
                    .uploadPartRequest(UploadPartRequest.builder().bucket(bucket).key(sourceKey)
                            .uploadId(uploadId).partNumber(partNumber).contentLength(expectedSize).build())
                    .build());
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                if (!name.equalsIgnoreCase("host")) {
                    headers.put(name, String.join(",", values));
                }
            });
            return new PartGrant(signed.url().toString(), "PUT", headers, signedAt.plus(ttl), expectedSize);
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public List<RemotePart> listParts(String sourceKey, String uploadId) {
        try {
            List<RemotePart> parts = new ArrayList<>();
            for (var page : s3.listPartsPaginator(ListPartsRequest.builder().bucket(bucket).key(sourceKey)
                    .uploadId(uploadId).build())) {
                page.parts().forEach(part -> parts.add(new RemotePart(part.partNumber(), part.eTag(), part.size())));
            }
            return parts;
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public void complete(String sourceKey, String uploadId, List<CompletePart> parts) {
        try {
            List<CompletedPart> completed = parts.stream().map(part -> CompletedPart.builder()
                    .partNumber(part.partNumber()).eTag(part.etag()).build()).toList();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder().bucket(bucket).key(sourceKey)
                    .uploadId(uploadId).multipartUpload(CompletedMultipartUpload.builder().parts(completed).build()).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404 && head(sourceKey).isPresent()) {
                return;
            }
            throw unavailable();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public Optional<StoredObject> head(String sourceKey) {
        try {
            var object = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(sourceKey).build());
            return Optional.of(new StoredObject(object.contentLength(), object.contentType()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw unavailable();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public void abort(String sourceKey, String uploadId) {
        if (uploadId == null) {
            return;
        }
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(sourceKey)
                    .uploadId(uploadId).build());
        } catch (S3Exception ex) {
            if (ex.statusCode() != 404) {
                throw unavailable();
            }
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public List<String> findMultipartUploadIds(String sourceKey) {
        try {
            List<String> ids = new ArrayList<>();
            for (var page : s3.listMultipartUploadsPaginator(ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix(sourceKey).build())) {
                page.uploads().stream().filter(upload -> sourceKey.equals(upload.key()))
                        .forEach(upload -> ids.add(upload.uploadId()));
            }
            return ids;
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        try {
            for (var page : s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())) {
                page.contents().forEach(object -> s3.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(object.key()).build()));
            }
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public void abortPrefix(String prefix) {
        try {
            for (var page : s3.listMultipartUploadsPaginator(ListMultipartUploadsRequest.builder()
                    .bucket(bucket).prefix(prefix).build())) {
                page.uploads().forEach(upload -> abort(upload.key(), upload.uploadId()));
            }
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public boolean prefixEmpty(String prefix) {
        try {
            return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                    .maxKeys(1).build()).contents().isEmpty();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public StorageAccess scopedAccess(String sourceKey, String outputPrefix, Instant absoluteDeadline) {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[" +
                "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/" + sourceKey + "\"]}," +
                "{\"Effect\":\"Allow\",\"Action\":[\"s3:PutObject\"],\"Resource\":[\"arn:aws:s3:::" + bucket + "/" + outputPrefix + "*\"]}]}";
        try {
            var response = sts.assumeRole(AssumeRoleRequest.builder()
                    .roleArn("arn:aws:iam::000000000000:role/video-worker")
                    .roleSessionName("video-" + java.util.UUID.randomUUID())
                    .durationSeconds(1200).policy(policy).build());
            var credentials = response.credentials();
            Instant expiry = credentials.expiration().isBefore(absoluteDeadline)
                    ? credentials.expiration() : absoluteDeadline;
            return new StorageAccess(endpoint, bucket, region, credentials.accessKeyId(),
                    credentials.secretAccessKey(), credentials.sessionToken(), expiry);
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public String readText(String objectKey) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
                    .asUtf8String();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    @Override
    public String signRead(String objectKey, Duration ttl) {
        try {
            return presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(ttl)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(objectKey).build()).build())
                    .url().toString();
        } catch (SdkException ex) {
            throw unavailable();
        }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "Медиа-хранилище временно недоступно");
    }
}
