package com.elebusiness.service.video;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VideoOutputNormalizerTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsSupportedRatiosToFixedDimensions() {
        VideoOutputNormalizer normalizer = normalizer((command, timeout) -> result(0, ""));

        assertEquals(new VideoOutputNormalizer.TargetSize(1280, 720), normalizer.targetSize("16:9"));
        assertEquals(new VideoOutputNormalizer.TargetSize(720, 1280), normalizer.targetSize("9:16"));
        assertEquals(new VideoOutputNormalizer.TargetSize(720, 720), normalizer.targetSize("1:1"));
        assertEquals(new VideoOutputNormalizer.TargetSize(960, 720), normalizer.targetSize("4:3"));
        assertEquals(new VideoOutputNormalizer.TargetSize(720, 960), normalizer.targetSize("3:4"));
        assertEquals(new VideoOutputNormalizer.TargetSize(1080, 720), normalizer.targetSize("3:2"));
        assertEquals(new VideoOutputNormalizer.TargetSize(720, 1080), normalizer.targetSize("2:3"));
        assertEquals(new VideoOutputNormalizer.TargetSize(1280, 720), normalizer.targetSize("unknown"));
    }

    @Test
    void transcodesMismatchedVideoToSelectedFixedSize() throws Exception {
        Path source = tempDir.resolve("input.mp4");
        Files.writeString(source, "source", StandardCharsets.UTF_8);
        List<List<String>> commands = new ArrayList<>();
        AtomicInteger probeCount = new AtomicInteger();
        VideoOutputNormalizer normalizer = normalizer((command, timeout) -> {
            commands.add(List.copyOf(command));
            if ("ffprobe-test".equals(command.get(0))) {
                return probeCount.getAndIncrement() == 0
                        ? result(0, probe(1280, 720, "h264", "yuv420p"))
                        : result(0, probe(720, 960, "h264", "yuv420p"));
            }
            Files.writeString(Path.of(command.get(command.size() - 1)), "normalized", StandardCharsets.UTF_8);
            return result(0, "");
        });

        String normalized = normalizer.normalize(source.toString(), "3:4");

        assertEquals(source.toString(), normalized);
        assertEquals("normalized", Files.readString(source, StandardCharsets.UTF_8));
        List<String> ffmpeg = commands.stream().filter(command -> "ffmpeg-test".equals(command.get(0))).findFirst().orElseThrow();
        assertTrue(ffmpeg.contains("scale=720:960:force_original_aspect_ratio=increase,crop=720:960,setsar=1"));
        assertTrue(ffmpeg.contains("libx264"));
        assertTrue(ffmpeg.contains("yuv420p"));
        assertTrue(ffmpeg.contains("+faststart"));
    }

    @Test
    void remuxesMatchingH264VideoWithoutReencoding() throws Exception {
        Path source = tempDir.resolve("matching.mp4");
        Files.writeString(source, "source", StandardCharsets.UTF_8);
        List<List<String>> commands = new ArrayList<>();
        VideoOutputNormalizer normalizer = normalizer((command, timeout) -> {
            commands.add(List.copyOf(command));
            if ("ffprobe-test".equals(command.get(0))) {
                return result(0, probe(1280, 720, "h264", "yuv420p"));
            }
            Files.writeString(Path.of(command.get(command.size() - 1)), "remuxed", StandardCharsets.UTF_8);
            return result(0, "");
        });

        normalizer.normalize(source.toString(), "16:9");

        List<String> ffmpeg = commands.stream().filter(command -> "ffmpeg-test".equals(command.get(0))).findFirst().orElseThrow();
        assertTrue(ffmpeg.contains("copy"));
        assertFalse(ffmpeg.contains("-vf"));
        assertEquals("remuxed", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void keepsOriginalFileWhenFfmpegFails() throws Exception {
        Path source = tempDir.resolve("failed.mp4");
        byte[] original = "source".getBytes(StandardCharsets.UTF_8);
        Files.write(source, original);
        VideoOutputNormalizer normalizer = normalizer((command, timeout) -> {
            if ("ffprobe-test".equals(command.get(0))) {
                return result(0, probe(1280, 720, "h264", "yuv420p"));
            }
            return result(1, "encode failed");
        });

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> normalizer.normalize(source.toString(), "3:4"));

        assertTrue(error.getMessage().contains("encode failed"));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertFalse(Files.exists(tempDir.resolve("failed.mp4.normalized.mp4")));
    }

    private VideoOutputNormalizer normalizer(VideoOutputNormalizer.CommandExecutor executor) {
        return new VideoOutputNormalizer(
                executor,
                "ffmpeg-test",
                "ffprobe-test",
                Duration.ofSeconds(5));
    }

    private VideoOutputNormalizer.CommandResult result(int exitCode, String output) {
        return new VideoOutputNormalizer.CommandResult(exitCode, output);
    }

    private String probe(int width, int height, String codec, String pixelFormat) {
        return "codec_name=" + codec + "\n"
                + "width=" + width + "\n"
                + "height=" + height + "\n"
                + "pix_fmt=" + pixelFormat + "\n";
    }
}
