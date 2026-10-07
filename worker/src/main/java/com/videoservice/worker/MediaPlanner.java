package com.videoservice.worker;

import java.util.ArrayList;
import java.util.List;

final class MediaPlanner {
    private MediaPlanner() { }

    record Rendition(String quality, int width, int height) { }

    static List<Rendition> plan(int width, int height) {
        if (width < 2 || height < 2) throw new IllegalArgumentException("Invalid video dimensions");
        List<Rendition> result = new ArrayList<>();
        for (int target : new int[]{360, 720, 1080}) {
            if (height >= target) {
                int targetWidth = Math.max(2, (int) Math.round((double) width * target / height / 2) * 2);
                result.add(new Rendition(target + "p", targetWidth, target));
            }
        }
        if (result.isEmpty()) result.add(new Rendition("source", width / 2 * 2, height / 2 * 2));
        return result;
    }
}
