package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GptImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(GptImageAgent.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties appProperties;

    public GptImageAgent(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public String getId() { return "gpt-image"; }

    @Override
    public String getDisplayName() { return "GPT-Image 2"; }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        String apiKey  = appProperties.getGptImage().getApiKey();
        String baseUrl = appProperties.getGptImage().getBaseUrl();

        if (apiKey == null || apiKey.isBlank()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "gpt-image-2");
            payload.put("prompt", prompt != null ? prompt : "product photo");
            payload.put("size", "1024x1024");
            payload.put("quality", "standard");
            payload.put("output_format", "png");
            payload.put("n", 1);

            String jsonBody = mapper.writeValueAsString(payload);

            URL url = new URL(baseUrl + "/v1/images/generations");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status < 200 || status >= 300) {
                log.error("GPT-Image 请求失败 ({}): {}", status, respBody);
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(respBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
            if (data == null || data.isEmpty()) {
                log.error("GPT-Image 响应中无 data 字段");
                return false;
            }

            Map<String, Object> item = data.get(0);

            if (item.containsKey("b64_json")) {
                byte[] imgBytes = Base64.getDecoder().decode((String) item.get("b64_json"));
                new File(outputPath).getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    fos.write(imgBytes);
                }
                log.info("GPT-Image 生成成功 (base64) -> {}", outputPath);
                return true;
            }

            if (item.containsKey("url")) {
                return downloadUrl((String) item.get("url"), outputPath);
            }

            log.error("GPT-Image 响应中既无 b64_json 也无 url");
            return false;

        } catch (Exception e) {
            log.error("GPT-Image 生成异常: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean downloadUrl(String imgUrl, String outputPath) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(imgUrl).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            new File(outputPath).getParentFile().mkdirs();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {
                in.transferTo(fos);
            }
            conn.disconnect();
            log.info("GPT-Image 生成成功 (url) -> {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("GPT-Image 下载图片失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
