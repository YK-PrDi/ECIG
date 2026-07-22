package com.elebusiness.service.video;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 视频提供商连通性测试服务
 * 通过最小化API调用测试各提供商是否可达,不实际生成视频
 */
@Service
public class VideoConnectivityTestService {

    private static final Logger log = LoggerFactory.getLogger(VideoConnectivityTestService.class);
    private static final int TEST_TIMEOUT_SECONDS = 10;

    private final AppProperties properties;
    private final VideoModelCatalog catalog;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient testClient;

    public VideoConnectivityTestService(AppProperties properties, VideoModelCatalog catalog) {
        this.properties = properties;
        this.catalog = catalog;
        this.testClient = new OkHttpClient.Builder()
                .connectTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    public List<ConnectivityResult> testAll() {
        List<ConnectivityResult> results = new ArrayList<>();

        // 测试所有已配置的提供商
        for (VideoModelCatalog.ModelView model : catalog.models()) {
            if (!model.configured()) {
                results.add(new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "SKIPPED",
                        0,
                        "API Key 未配置",
                        null
                ));
                continue;
            }

            // 跳过重复的提供商(同一提供商可能有多个模型)
            if (results.stream().anyMatch(r -> r.providerId().equals(model.providerId()))) {
                continue;
            }

            ConnectivityResult result = testProvider(model);
            results.add(result);
        }

        return results;
    }

    private ConnectivityResult testProvider(VideoModelCatalog.ModelView model) {
        long startTime = System.currentTimeMillis();
        try {
            return switch (model.provider()) {
                case VEO -> testVeo(model, startTime);
                case SEEDANCE -> testSeedance(model, startTime);
                case SUIXIANG_GROK -> testSuixiangGrok(model, startTime);
                case SUIXIANG_JIMENG -> testSuixiangJimeng(model, startTime);
            };
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.warn("[connectivity] {} 测试失败: {}", model.providerLabel(), e.getMessage());
            return new ConnectivityResult(
                    model.providerLabel(),
                    model.providerId(),
                    "ERROR",
                    latency,
                    e.getMessage(),
                    null
            );
        }
    }

    private ConnectivityResult testVeo(VideoModelCatalog.ModelView model, long startTime) throws Exception {
        // Veo使用Gemini API,测试模型列表接口
        String apiKey = properties.getGemini().getApiKey();
        String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + apiKey;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = testClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - startTime;
            if (response.isSuccessful()) {
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "OK",
                        latency,
                        null,
                        "Gemini API 可达"
                );
            } else {
                String body = response.body() != null ? response.body().string() : "";
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "FAILED",
                        latency,
                        "HTTP " + response.code() + ": " + body.substring(0, Math.min(100, body.length())),
                        null
                );
            }
        }
    }

    private ConnectivityResult testSeedance(VideoModelCatalog.ModelView model, long startTime) throws Exception {
        // Seedance(火山方舟)测试账户余额接口
        String apiKey = properties.getVolcengine().getApiKey();
        String baseUrl = properties.getVolcengine().getBaseUrl();
        String url = baseUrl + "/api/v1/account";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = testClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - startTime;
            if (response.isSuccessful()) {
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "OK",
                        latency,
                        null,
                        "火山方舟 API 可达"
                );
            } else {
                String body = response.body() != null ? response.body().string() : "";
                String error = body;
                try {
                    JsonNode json = objectMapper.readTree(body);
                    if (json.has("message")) {
                        error = json.get("message").asText();
                    }
                } catch (Exception ignored) {
                }
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        response.code() == 403 ? "AUTH_FAILED" : "FAILED",
                        latency,
                        "HTTP " + response.code() + ": " + error,
                        null
                );
            }
        }
    }

    private ConnectivityResult testSuixiangGrok(VideoModelCatalog.ModelView model, long startTime) throws Exception {
        // Grok测试模型列表接口
        String baseUrl = properties.getSuiXiangVideo().getBaseUrl();
        String apiKey = properties.getSuiXiangVideo().getGrok().getApiKey();
        String url = baseUrl + "/models";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = testClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - startTime;
            if (response.isSuccessful()) {
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "OK",
                        latency,
                        null,
                        "Grok API 可达"
                );
            } else {
                String body = response.body() != null ? response.body().string() : "";
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "FAILED",
                        latency,
                        "HTTP " + response.code() + ": " + body.substring(0, Math.min(100, body.length())),
                        null
                );
            }
        }
    }

    private ConnectivityResult testSuixiangJimeng(VideoModelCatalog.ModelView model, long startTime) throws Exception {
        // 即梦测试模型列表接口
        String baseUrl = properties.getSuiXiangVideo().getBaseUrl();
        String apiKey = properties.getSuiXiangVideo().getJimeng().getApiKey();
        String url = baseUrl + "/models";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();

        try (Response response = testClient.newCall(request).execute()) {
            long latency = System.currentTimeMillis() - startTime;
            if (response.isSuccessful()) {
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "OK",
                        latency,
                        null,
                        "即梦 API 可达"
                );
            } else {
                String body = response.body() != null ? response.body().string() : "";
                return new ConnectivityResult(
                        model.providerLabel(),
                        model.providerId(),
                        "FAILED",
                        latency,
                        "HTTP " + response.code() + ": " + body.substring(0, Math.min(100, body.length())),
                        null
                );
            }
        }
    }

    public record ConnectivityResult(
            String provider,
            String providerId,
            String status,
            long latencyMs,
            String error,
            String message
    ) {
    }
}
