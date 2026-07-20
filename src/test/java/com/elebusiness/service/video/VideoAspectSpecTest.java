package com.elebusiness.service.video;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VideoAspectSpecTest {

    @Test
    void mapsEverySupportedAspectToOneCanonicalSize() {
        Map<String, String> expected = Map.of(
                "16:9", "1280x720",
                "9:16", "720x1280",
                "1:1", "720x720",
                "4:3", "960x720",
                "3:4", "720x960",
                "3:2", "1080x720",
                "2:3", "720x1080");

        expected.forEach((aspect, size) -> {
            VideoAspectSpec spec = VideoAspectSpec.resolve(aspect);
            assertEquals(aspect, spec.aspectRatio());
            assertEquals(size, spec.apiSize());
            assertEquals(size, spec.width() + "x" + spec.height());
        });
    }

    @Test
    void fallsBackToSixteenByNineForMissingOrUnsupportedAspect() {
        assertEquals("1280x720", VideoAspectSpec.resolve(null).apiSize());
        assertEquals("1280x720", VideoAspectSpec.resolve("").apiSize());
        assertEquals("1280x720", VideoAspectSpec.resolve("unsupported").apiSize());
    }
}
