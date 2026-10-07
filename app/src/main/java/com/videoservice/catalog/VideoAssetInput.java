package com.videoservice.catalog;

public record VideoAssetInput(String kind, String quality, String objectKey, String renditionPrefix,
                              Integer segmentCount, long sizeBytes) {
}
