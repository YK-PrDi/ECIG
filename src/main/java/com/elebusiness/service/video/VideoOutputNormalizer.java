package com.elebusiness.service.video;

import com.elebusiness.service.agent.GenerationCancellationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class VideoOutputNormalizer {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final CommandExecutor commandExecutor;
    private final String ffmpegBin;
    private final String ffprobeBin;
    private final Duration timeout;

    @Autowired
    public VideoOutputNormalizer() {
        this(
                new ProcessCommandExecutor(),
                environmentOrDefault("FFMPEG_BIN", "ffmpeg"),
                environmentOrDefault("FFPROBE_BIN", "ffprobe"),
                DEFAULT_TIMEOUT);
    }

    VideoOutputNormalizer(
            CommandExecutor commandExecutor,
            String ffmpegBin,
            String ffprobeBin,
            Duration timeout) {
        this.commandExecutor = commandExecutor;
        this.ffmpegBin = ffmpegBin;
        this.ffprobeBin = ffprobeBin;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_TIMEOUT
                : timeout;
    }

    public String normalize(String videoPath, String aspectRatio) throws Exception {
        Path source = Path.of(videoPath);
        if (!Files.isRegularFile(source) || Files.size(source) == 0) {
            throw new IOException("视频规范化失败：源文件不存在或为空");
        }

        TargetSize target = targetSize(aspectRatio);
        ProbeResult sourceProbe = probe(source);
        Path normalized = source.resolveSibling(source.getFileName() + ".normalized.mp4");
        Files.deleteIfExists(normalized);
        try {
            List<String> command = sourceProbe.matches(target)
                    ? remuxCommand(source, normalized)
                    : transcodeCommand(source, normalized, target);
            CommandResult result = commandExecutor.execute(command, timeout);
            if (result.exitCode() != 0) {
                throw new IllegalStateException("视频格式规范化失败: " + summarize(result.output()));
            }
            if (!Files.isRegularFile(normalized) || Files.size(normalized) == 0) {
                throw new IOException("视频格式规范化失败：FFmpeg 未生成有效文件");
            }

            ProbeResult normalizedProbe = probe(normalized);
            if (!normalizedProbe.matches(target)) {
                throw new IOException("视频格式规范化失败：实际输出为 "
                        + normalizedProbe.width() + "x" + normalizedProbe.height()
                        + " / " + normalizedProbe.codec() + " / " + normalizedProbe.pixelFormat());
            }
            replace(normalized, source);
            return source.toString();
        } catch (Exception e) {
            Files.deleteIfExists(normalized);
            throw e;
        }
    }

    TargetSize targetSize(String aspectRatio) {
        VideoAspectSpec spec = VideoAspectSpec.resolve(aspectRatio);
        return new TargetSize(spec.width(), spec.height());
    }

    private ProbeResult probe(Path video) throws Exception {
        List<String> command = List.of(
                ffprobeBin,
                "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name,width,height,pix_fmt",
                "-of", "default=noprint_wrappers=1",
                video.toString());
        CommandResult result = commandExecutor.execute(command, timeout);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("视频信息读取失败: " + summarize(result.output()));
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : result.output().split("\\R")) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        }
        try {
            return new ProbeResult(
                    Integer.parseInt(values.getOrDefault("width", "0")),
                    Integer.parseInt(values.getOrDefault("height", "0")),
                    values.getOrDefault("codec_name", ""),
                    values.getOrDefault("pix_fmt", ""));
        } catch (NumberFormatException e) {
            throw new IOException("视频信息读取失败：无法解析 FFprobe 输出", e);
        }
    }

    private List<String> remuxCommand(Path source, Path output) {
        return List.of(
                ffmpegBin,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c:v", "copy",
                "-c:a", "aac",
                "-movflags", "+faststart",
                output.toString());
    }

    private List<String> transcodeCommand(Path source, Path output, TargetSize target) {
        List<String> command = new ArrayList<>();
        command.addAll(List.of(
                ffmpegBin,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(),
                "-map", "0:v:0",
                "-map", "0:a?",
                "-vf", "scale=" + target.width() + ":" + target.height()
                        + ":force_original_aspect_ratio=increase,crop="
                        + target.width() + ":" + target.height() + ",setsar=1",
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", "20",
                "-pix_fmt", "yuv420p",
                "-c:a", "aac",
                "-movflags", "+faststart",
                output.toString()));
        return List.copyOf(command);
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String summarize(String output) {
        String normalized = output == null ? "" : output.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return "未返回错误详情";
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    record TargetSize(int width, int height) {
    }

    private record ProbeResult(int width, int height, String codec, String pixelFormat) {
        private boolean matches(TargetSize target) {
            return width == target.width()
                    && height == target.height()
                    && "h264".equalsIgnoreCase(codec)
                    && "yuv420p".equalsIgnoreCase(pixelFormat);
        }
    }

    @FunctionalInterface
    interface CommandExecutor {
        CommandResult execute(List<String> command, Duration timeout) throws Exception;
    }

    record CommandResult(int exitCode, String output) {
    }

    private static final class ProcessCommandExecutor implements CommandExecutor {
        @Override
        public CommandResult execute(List<String> command, Duration timeout) throws Exception {
            Process process;
            try {
                process = new ProcessBuilder(command).redirectErrorStream(true).start();
            } catch (IOException e) {
                throw new IOException("无法启动视频处理工具 " + command.get(0) + "，请确认 FFmpeg 已安装", e);
            }

            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return e.getMessage() == null ? "" : e.getMessage();
                }
            });
            try (GenerationCancellationContext.Registration ignored =
                         GenerationCancellationContext.register(process::destroyForcibly)) {
                boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                    throw new IllegalStateException("视频格式规范化超时");
                }
                return new CommandResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("视频格式规范化已取消", e);
            }
        }
    }
}
