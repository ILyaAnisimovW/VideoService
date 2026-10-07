package com.videoservice.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaPlannerTest {
    @Test
    void neverUpscalesAndProvidesOneLowResolutionRendition() {
        assertEquals(List.of("source"), MediaPlanner.plan(320, 240).stream()
                .map(MediaPlanner.Rendition::quality).toList());
        assertEquals(List.of("360p", "720p"), MediaPlanner.plan(1280, 720).stream()
                .map(MediaPlanner.Rendition::quality).toList());
        assertTrue(MediaPlanner.plan(1920, 1080).stream().allMatch(item -> item.height() <= 1080));
        assertThrows(IllegalArgumentException.class, () -> MediaPlanner.plan(0, 720));
    }
}
