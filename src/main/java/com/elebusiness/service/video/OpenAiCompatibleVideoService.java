package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.agent.GenerationCancellationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAiCompatibleVideoService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleVideoService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\]\\[()<>\\\"']+");
    private static final List<String> URL_KEYS = List.of("video_url", "file_url", "download_url", "url");
    private static final List<String> BASE64_KEYS = List.of("b64_json", "base64", "video_base64");
    private static final Set<String> PENDING_STATUSES = Set.of(
            "queued", "pending", "processing", "in_progress", "running", "created");
    private static final Set<String> SUCCESS_STATUSES = Set.of(
            "completed", "succeeded", "success", "done");
    private static final Set<String> FAILED_STATUSES = Set.of(
            "failed", "error", "cancelled", "canceled", "expired");
    private static final long DEFAULT_POLL_INTERVAL_MILLIS = 5_000L;
    private static final int MEDIA_DOWNLOAD_ATTEMPTS = 3;
    private static final long DEFAULT_MEDIA_RETRY_DELAY_MILLIS = 1_000L;
    private static final long MAX_RATE_LIMIT_BACKOFF_MILLIS = 30_000L;
    private static final Duration DEFAULT_GENERATION_TIMEOUT = Duration.ofMinutes(20);

    private final AppProperties properties;
    private final VideoModelCatalog catalog;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long pollIntervalMillis;
    private final Duration generationTimeout;
    private final long mediaRetryDelayMillis;
    private final OkHttpClient apiClient;
    private final VideoMediaProxyPolicy mediaProxyPolicy;
    /** 传递当前轮询请求的 apiKey，供 findMedia 解析相对路径视频地址时携带认证 */
    private final ThreadLocal<String> currentApiKeyHolder = new ThreadLocal<>();

    @Autowired
    public OpenAiCompatibleVideoService(AppProperties properties, VideoModelCatalog catalog) {
        this(properties, catalog, DEFAULT_POLL_INTERVAL_MILLIS, DEFAULT_GENERATION_TIMEOUT,
                DEFAULT_MEDIA_RETRY_DELAY_MILLIS);
    }

    OpenAiCompatibleVideoService(
            AppProperties properties,
            VideoModelCatalog catalog,
            long pollIntervalMillis,
            Duration generationTimeout) {
        this(properties, catalog, pollIntervalMillis, generationTimeout, DEFAULT_MEDIA_RETRY_DELAY_MILLIS);
    }

    OpenAiCompatibleVideoService(
            AppProperties properties,
            VideoModelCatalog catalog,
            long pollIntervalMillis,
            Duration generationTimeout,
            long mediaRetryDelayMillis) {
        this.properties = properties;
        this.catalog = catalog;
        this.pollIntervalMillis = Math.max(1L, pollIntervalMillis);
        this.generationTimeout = generationTimeout == null || generationTimeout.isNegative() || generationTimeout.isZero()
                ? DEFAULT_GENERATION_TIMEOUT
                : generationTimeout;
        this.mediaRetryDelayMillis = Math.max(1L, mediaRetryDelayMillis);
        this.apiClient = buildApiClient();
        this.mediaProxyPolicy = new VideoMediaProxyPolicy(properties);
    }

    public String generateVideo(VideoModelCatalog.ModelView model,
                                String prompt,
                                List<String> imageDataUris,
                                String aspectRatio,
                                int durationSeconds,
                                String outputPath) throws Exception {
        AppProperties.ProviderCredential credential = catalog.credentialFor(model);
        if (!credential.isConfigured()) {
            String variable = model.provider() == VideoModelCatalog.Provider.SUIXIANG_GROK
                    ? "SUIXIANG_GROK_VIDEO_API_KEY"
                    : "SUIXIANG_JIMENG_VIDEO_API_KEY";
            throw new IllegalStateException(model.providerLabel() + " API Key 未配置，请填写 " + variable);
        }
        if (model.provider() != VideoModelCatalog.Provider.SUIXIANG_GROK
                && model.provider() != VideoModelCatalog.Provider.SUIXIANG_JIMENG) {
            throw new IllegalArgumentException("该服务不处理模型: " + model.id());
        }

        Path output = Path.of(outputPath);
        Path partial = output.resolveSibling(output.getFileName() + ".part");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.deleteIfExists(partial);
        currentApiKeyHolder.set(credential.getApiKey());
        try {
            JsonNode submission = switch (model.provider()) {
                case SUIXIANG_GROK -> submitGrok(
                        model, credential.getApiKey(), prompt, imageDataUris, aspectRatio, durationSeconds);
                case SUIXIANG_JIMENG -> submitJimeng(
                        model, credential.getApiKey(), prompt, imageDataUris, aspectRatio, durationSeconds);
                default -> throw new IllegalArgumentException("该服务不处理模型: " + model.id());
            };

            MediaResult media = findMedia(submission);
            if (media == null) {
                String taskId = extractTaskId(submission);
                if (taskId == null || taskId.isBlank()) {
                    throw new IOException("随想视频响应中未找到任务 ID 或可下载视频，响应结构: " + summarize(submission));
                }
                media = pollUntilComplete(model.provider(), credential.getApiKey(), taskId);
            }

            saveMedia(media, partial);
            ensureNotCancelled();
            moveCompletedFile(partial, output);
            return output.toString();
        } catch (Exception e) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(output);
            throw e;
        } finally {
            currentApiKeyHolder.remove();
        }
    }

    private JsonNode submitGrok(VideoModelCatalog.ModelView model,
                                String apiKey,
                                String prompt,
                                List<String> imageDataUris,
                                String aspectRatio,
                                int durationSeconds) throws Exception {
        List<String> images = imageDataUris == null
                ? List.of()
                : imageDataUris.stream().filter(value -> value != null && !value.isBlank()).toList();
        if (model.inputMode() == VideoModelCatalog.InputMode.TEXT_ONLY && !images.isEmpty()) {
            throw new IllegalArgumentException("Grok 文生视频不支持参考图");
        }
        if (model.inputMode() == VideoModelCatalog.InputMode.IMAGE_ONLY && images.size() != 1) {
            throw new IllegalArgumentException("Grok 图生视频必须上传且只能上传一张参考图");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model.id());
        body.put("prompt", normalizePrompt(prompt));
        body.put("duration", Math.max(1, Math.min(15, durationSeconds)));
        body.put("aspect_ratio", normalizeGrokAspectRatio(aspectRatio));
        body.put("resolution", "720p");
        if (model.inputMode() == VideoModelCatalog.InputMode.IMAGE_ONLY) {
            body.put("image_url", images.get(0));
        }

        Request request = authorizedRequest(apiKey, "/videos/generations")
                .post(RequestBody.create(objectMapper.writeValueAsBytes(body), JSON))
                .build();
        return executeJson(request);
    }

    private JsonNode submitJimeng(VideoModelCatalog.ModelView model,
                                  String apiKey,
                                  String prompt,
                                  List<String> imageDataUris,
                                  String aspectRatio,
                                  int durationSeconds) throws Exception {
        MultipartBody.Builder body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model.id())
                .addFormDataPart("prompt", normalizePrompt(prompt))
                .addFormDataPart("seconds", String.valueOf(Math.max(1, Math.min(15, durationSeconds))))
                .addFormDataPart("size", toVideoSize(aspectRatio));
        firstImage(imageDataUris).ifPresent(image -> {
            DataUri dataUri = decodeDataUri(image);
            body.addFormDataPart(
                    "input_reference",
                    dataUri.fileName(),
                    RequestBody.create(dataUri.bytes(), MediaType.get(dataUri.mediaType())));
        });

        Request request = authorizedRequest(apiKey, "/videos")
                .post(body.build())
                .build();
        return executeJson(request);
    }

    private MediaResult pollUntilComplete(
            VideoModelCatalog.Provider provider,
            String apiKey,
            String taskId) throws Exception {
        long deadline = System.nanoTime() + generationTimeout.toNanos();
        String encodedTaskId = URLEncoder.encode(taskId, StandardCharsets.UTF_8);
        String statusPath = "/videos/" + encodedTaskId;
        long rateLimitBackoffMillis = pollIntervalMillis;

        while (System.nanoTime() < deadline) {
            ensureNotCancelled();
            final JsonNode statusResponse;
            try {
                statusResponse = executeJson(authorizedRequest(apiKey, statusPath).get().build());
                rateLimitBackoffMillis = pollIntervalMillis;
            } catch (ProviderRateLimitException e) {
                long waitMillis = Math.max(rateLimitBackoffMillis, e.retryAfterMillis());
                waitForNextPoll(deadline, waitMillis);
                rateLimitBackoffMillis = Math.min(
                        MAX_RATE_LIMIT_BACKOFF_MILLIS,
                        Math.max(pollIntervalMillis, waitMillis) * 2);
                continue;
            }
            MediaResult media = findMedia(statusResponse);
            if (media != null) return media;

            String status = extractStatus(statusResponse);
            if (FAILED_STATUSES.contains(status)) {
                throw new IllegalStateException("随想视频生成失败: " + extractErrorMessage(statusResponse));
            }
            if (SUCCESS_STATUSES.contains(status)) {
                if (provider == VideoModelCatalog.Provider.SUIXIANG_JIMENG) {
                    return new MediaResult(baseUrl() + statusPath + "/content", null, apiKey);
                }
                throw new IOException("随想 Grok 视频任务已完成但响应中没有视频地址，响应结构: " + summarize(statusResponse));
            }
            if (!status.isBlank() && !PENDING_STATUSES.contains(status)) {
                throw new IOException("随想视频返回未知任务状态 " + status + "，响应结构: " + summarize(statusResponse));
            }
            waitForNextPoll(deadline, pollIntervalMillis);
        }
        throw new IllegalStateException("随想视频生成超时，请稍后重试");
    }

    private void waitForNextPoll(long deadlineNanos, long requestedWaitMillis) throws Exception {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadlineNanos - System.nanoTime()));
        long waitMillis = Math.min(Math.max(1L, requestedWaitMillis), remainingMillis);
        if (waitMillis <= 0) return;
        CountDownLatch delay = new CountDownLatch(1);
        try (GenerationCancellationContext.Registration ignored = GenerationCancellationContext.register(delay::countDown)) {
            delay.await(waitMillis, TimeUnit.MILLISECONDS);
        }
        ensureNotCancelled();
    }

    private JsonNode executeJson(Request request) throws Exception {
        Call call = apiClient.newCall(request);
        try (GenerationCancellationContext.Registration ignored = GenerationCancellationContext.register(call::cancel);
             Response response = call.execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw providerError(response.code(), responseBody, response.header("Retry-After"));
            }
            if (responseBody.isBlank()) {
                throw new IOException("随想视频接口返回空响应");
            }
            try {
                return objectMapper.readTree(responseBody);
            } catch (Exception e) {
                throw new IOException("随想视频接口返回了非 JSON 响应: " + summarizeText(responseBody), e);
            }
        } catch (IOException e) {
            if (GenerationCancellationContext.isCancellationRequested()) {
                throw new IOException("视频任务已取消", e);
            }
            throw e;
        }
    }

    private void saveMedia(MediaResult media, Path partial) throws Exception {
        if (media.bytes() != null) {
            Files.write(partial, media.bytes());
        } else {
            downloadWithRetry(media.url(), partial, media.authorization());
        }
        if (!Files.exists(partial) || Files.size(partial) == 0) {
            throw new IOException("视频下载结果为空");
        }
    }

    private void downloadWithRetry(String url, Path partial, String apiKey) throws Exception {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MEDIA_DOWNLOAD_ATTEMPTS; attempt++) {
            ensureNotCancelled();
            Files.deleteIfExists(partial);
            try {
                downloadOnce(url, partial, apiKey);
                return;
            } catch (IOException e) {
                Files.deleteIfExists(partial);
                if (GenerationCancellationContext.isCancellationRequested()) {
                    throw e;
                }
                lastFailure = e;
                log.warn("视频媒体下载失败: host={}, attempt={}/{}, errorType={}",
                        mediaHost(url), attempt, MEDIA_DOWNLOAD_ATTEMPTS, e.getClass().getSimpleName());
                if (attempt < MEDIA_DOWNLOAD_ATTEMPTS) {
                    waitForMediaRetry();
                }
            }
        }
        throw lastFailure == null ? new IOException("视频媒体下载失败") : lastFailure;
    }

    private void downloadOnce(String url, Path partial, String apiKey) throws Exception {
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        Call call = mediaClient(url).newCall(requestBuilder.build());
        try (GenerationCancellationContext.Registration ignored = GenerationCancellationContext.register(call::cancel);
             Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("视频下载失败，HTTP " + response.code());
            }
            String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
            if (contentType.contains("application/json") || contentType.contains("text/html")) {
                throw new IOException("视频下载接口返回了非视频内容: " + contentType);
            }
            try (var input = response.body().byteStream(); var output = Files.newOutputStream(partial)) {
                input.transferTo(output);
            }
        } catch (IOException e) {
            if (GenerationCancellationContext.isCancellationRequested()) {
                throw new IOException("视频任务已取消", e);
            }
            throw e;
        }
    }

    private void waitForMediaRetry() throws Exception {
        CountDownLatch delay = new CountDownLatch(1);
        try (GenerationCancellationContext.Registration ignored = GenerationCancellationContext.register(delay::countDown)) {
            delay.await(mediaRetryDelayMillis, TimeUnit.MILLISECONDS);
        }
        ensureNotCancelled();
    }

    private Request.Builder authorizedRequest(String apiKey, String path) {
        return new Request.Builder()
                .url(baseUrl() + path)
                .header("Authorization", "Bearer " + apiKey);
    }

    private OkHttpClient buildApiClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.MINUTES)
                .writeTimeout(2, TimeUnit.MINUTES);
        AppProperties.Proxy proxy = properties.getProxy();
        if (proxy.isEnabled()) {
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        } else {
            builder.proxySelector(java.net.ProxySelector.getDefault());
        }
        return builder.build();
    }

    private OkHttpClient mediaClient(String mediaUrl) {
        return mediaProxyPolicy.proxyFor(mediaUrl)
                .map(proxy -> apiClient.newBuilder().proxy(proxy).build())
                .orElse(apiClient);
    }

    private String mediaHost(String mediaUrl) {
        try {
            String host = URI.create(mediaUrl).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }

    private MediaResult findMedia(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isTextual()) return fromText(node.asText());
        if (node.isArray()) {
            for (JsonNode item : node) {
                MediaResult result = findMedia(item);
                if (result != null) return result;
            }
            return null;
        }
        for (String key : BASE64_KEYS) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                try {
                    return new MediaResult(null, decodeBase64(value.asText()), null);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (String key : URL_KEYS) {
            MediaResult result = findMedia(node.get(key));
            if (result != null) return result;
        }
        for (String key : List.of("video", "data", "output", "result", "choices", "message", "content")) {
            MediaResult result = findMedia(node.get(key));
            if (result != null) return result;
        }
        return null;
    }

    private MediaResult fromText(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("data:video/") && trimmed.contains(",")) {
            try {
                return new MediaResult(null, decodeBase64(trimmed.substring(trimmed.indexOf(',') + 1)), null);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                MediaResult nested = findMedia(objectMapper.readTree(trimmed));
                if (nested != null) return nested;
            } catch (Exception ignored) {
            }
        }
        Matcher matcher = URL_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return new MediaResult(stripTrailingPunctuation(matcher.group()), null, null);
        }
        // Grok 等 provider 返回相对路径视频地址（如 /v1/videos/{id}/content），
        // 拼接 base-url 的 host 根，并携带 apiKey 以便下载受保护资源
        String relative = resolveRelativeMediaPath(trimmed);
        if (relative != null) {
            return new MediaResult(relative, null, currentApiKeyHolder.get());
        }
        return null;
    }

    /**
     * 将相对路径视频地址解析为完整 URL。
     * base-url 形如 https://host/v1，相对路径形如 /v1/videos/{id}/content，
     * 二者可能重复 /v1 前缀，需按 host 根拼接避免出现 /v1/v1。
     */
    private String resolveRelativeMediaPath(String value) {
        if (value == null) return null;
        String path = value.trim();
        if (!path.startsWith("/") || path.contains(" ")) return null;
        if (!(path.contains("/video") || path.endsWith("/content"))) return null;
        String base = baseUrl();
        try {
            java.net.URI baseUri = java.net.URI.create(base);
            String origin = baseUri.getScheme() + "://" + baseUri.getAuthority();
            return origin + path;
        } catch (Exception e) {
            // 兜底：去掉 base 末尾的 /vN 段后拼接
            String origin = base.replaceAll("/v\\d+$", "");
            return origin + path;
        }
    }

    private String extractTaskId(JsonNode root) {
        return firstText(root, List.of("request_id", "task_id", "taskId", "id"), List.of("data", "result", "output"));
    }

    private String extractStatus(JsonNode root) {
        String status = firstText(root, List.of("status", "state"), List.of("data", "result", "output"));
        return status == null ? "" : status.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private String extractErrorMessage(JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        if (error != null) {
            if (error.isTextual() && !error.asText().isBlank()) return summarizeText(error.asText());
            JsonNode message = error.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return summarizeText(message.asText());
            }
        }
        String message = firstText(root, List.of("message", "msg", "detail"), List.of("data", "result"));
        return message == null || message.isBlank() ? summarize(root) : summarizeText(message);
    }

    private String firstText(JsonNode root, List<String> keys, List<String> containers) {
        if (root == null || !root.isObject()) return null;
        for (String key : keys) {
            JsonNode value = root.get(key);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) return value.asText();
        }
        for (String container : containers) {
            JsonNode nested = root.get(container);
            if (nested == null) continue;
            if (nested.isArray()) {
                for (JsonNode item : nested) {
                    String value = firstText(item, keys, List.of());
                    if (value != null) return value;
                }
            } else {
                String value = firstText(nested, keys, List.of());
                if (value != null) return value;
            }
        }
        return null;
    }

    private java.util.Optional<String> firstImage(List<String> imageDataUris) {
        if (imageDataUris == null) return java.util.Optional.empty();
        return imageDataUris.stream().filter(value -> value != null && !value.isBlank()).findFirst();
    }

    private DataUri decodeDataUri(String value) {
        int comma = value.indexOf(',');
        if (!value.startsWith("data:") || comma < 0 || !value.substring(0, comma).contains(";base64")) {
            throw new IllegalArgumentException("即梦参考图必须是 Base64 data URI");
        }
        String mediaType = value.substring(5, value.indexOf(';'));
        byte[] bytes = decodeBase64(value.substring(comma + 1));
        String extension = switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "png";
        };
        return new DataUri(mediaType, "input." + extension, bytes);
    }

    private byte[] decodeBase64(String value) {
        String raw = value;
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma >= 0) raw = raw.substring(comma + 1);
        return Base64.getDecoder().decode(raw.replaceAll("\\s+", ""));
    }

    private String normalizePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        if (value.isBlank()) throw new IllegalArgumentException("视频提示词不能为空");
        return value;
    }

    private String normalizeGrokAspectRatio(String aspectRatio) {
        String value = aspectRatio == null ? "" : aspectRatio.trim();
        return Set.of("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3").contains(value)
                ? value
                : "16:9";
    }

    private String toVideoSize(String aspectRatio) {
        return VideoAspectSpec.resolve(aspectRatio).apiSize();
    }

    private void ensureNotCancelled() throws IOException {
        if (GenerationCancellationContext.isCancellationRequested()) {
            throw new IOException("视频任务已取消");
        }
    }

    private void moveCompletedFile(Path partial, Path output) throws IOException {
        try {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private RuntimeException providerError(int status, String responseBody, String retryAfter) {
        String detail = summarizeText(responseBody == null ? "" : responseBody);
        return switch (status) {
            case 401, 403 -> new IllegalStateException("随想视频鉴权失败，请检查对应 API Key");
            case 429 -> new ProviderRateLimitException(
                    "随想视频接口限流，请稍后重试",
                    parseRetryAfterMillis(retryAfter));
            default -> new IllegalStateException(
                    "随想视频接口请求失败，HTTP " + status + (detail.isBlank() ? "" : ": " + detail));
        };
    }

    private long parseRetryAfterMillis(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Math.max(0L, Long.parseLong(value.trim()) * 1_000L);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return Math.max(0L, Duration.between(Instant.now(), retryAt).toMillis());
            } catch (Exception ignoredDate) {
                return 0L;
            }
        }
    }

    private String summarize(JsonNode root) {
        return root == null ? "null" : summarizeText(root.toString());
    }

    private String summarizeText(String value) {
        String detail = value == null ? "" : value
                .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "Bearer ***")
                .replaceAll("(?i)sk-[a-z0-9_-]{12,}", "sk-***");
        return detail.length() > 600 ? detail.substring(0, 600) + "..." : detail;
    }

    private String baseUrl() {
        String value = properties.getSuiXiangVideo().getBaseUrl();
        String baseUrl = value == null || value.isBlank() ? "https://sui-xiang.com/v1" : value.trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        return baseUrl;
    }

    private String stripTrailingPunctuation(String value) {
        String result = value;
        while (!result.isEmpty() && ".,;:!?。，；：！？".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record MediaResult(String url, byte[] bytes, String authorization) {
    }

    private record DataUri(String mediaType, String fileName, byte[] bytes) {
    }

    private static final class ProviderRateLimitException extends IllegalStateException {
        private final long retryAfterMillis;

        private ProviderRateLimitException(String message, long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        private long retryAfterMillis() {
            return retryAfterMillis;
        }
    }
}
