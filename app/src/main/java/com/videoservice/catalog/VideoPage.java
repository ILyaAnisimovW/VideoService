package com.videoservice.catalog;

import java.util.List;

public record VideoPage(List<VideoView> items, String nextCursor) {
}
