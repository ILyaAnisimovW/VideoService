package com.videoservice.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;

@Configuration
public class S3StorageConfig {
    @Bean
    S3Client s3Client(@Value("${video.storage.endpoint}") String endpoint,
                      @Value("${video.storage.region}") String region,
                      @Value("${video.storage.access-key}") String accessKey,
                      @Value("${video.storage.secret-key}") String secretKey) {
        return S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${video.storage.public-endpoint}") String endpoint,
                            @Value("${video.storage.region}") String region,
                            @Value("${video.storage.access-key}") String accessKey,
                            @Value("${video.storage.secret-key}") String secretKey) {
        return S3Presigner.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();
    }

    @Bean
    StsClient stsClient(@Value("${video.storage.endpoint}") String endpoint,
                        @Value("${video.storage.region}") String region,
                        @Value("${video.storage.access-key}") String accessKey,
                        @Value("${video.storage.secret-key}") String secretKey) {
        return StsClient.builder().endpointOverride(URI.create(endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
