package com.videoservice.worker;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.nio.file.Path;

@Component
class WorkerStorage {
    S3Client connect(WorkerMessages.StorageAccess access) {
        return S3Client.builder().endpointOverride(URI.create(access.endpoint()))
                .region(Region.of(access.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        access.accessKeyId(), access.secretAccessKey(), access.sessionToken())))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    void download(S3Client s3, WorkerMessages.Lease lease, Path target) {
        s3.getObject(GetObjectRequest.builder().bucket(lease.storageAccess().bucket())
                .key(lease.sourceKey()).build(), target);
    }

    void upload(S3Client s3, WorkerMessages.Lease lease, String key, Path source, String contentType) {
        if (!key.startsWith(lease.outputPrefix()) || key.contains("..")) {
            throw new IllegalArgumentException("Output key escapes attempt prefix");
        }
        s3.putObject(PutObjectRequest.builder().bucket(lease.storageAccess().bucket()).key(key)
                .contentType(contentType).build(), RequestBody.fromFile(source));
    }
}
