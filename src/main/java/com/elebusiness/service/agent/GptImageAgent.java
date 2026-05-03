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
import java.nio.file.Files;
import java.util.Base64;
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

        // 优先使用 whiteBgPath 作为编辑基底，其次 refImagePath，都没有则纯文生图
        File imageFile = resolveImageFile(whiteBgPath, refImagePath);
        if (imageFile != null) {
            return generateWithImage(prompt, imageFile, outputPath, apiKey, baseUrl);
        }
        return generateTextOnly(prompt, outputPath, apiKey, baseUrl);
    }

    private File resolveImageFile(String whiteBgPath, String refImagePath) {
        File wf = whiteBgPath != null ? new File(whiteBgPath) : null;
        if (wf != null && wf.exists()) return wf;
        File rf = refImagePath != null ? new File(refImagePath) : null;
        if (rf != null && rf.exists()) return rf;
        return null;
    }

    /** 有图片时调用 /v1/images/edits，图片作为编辑基底 */
    private boolean generateWithImage(String prompt, File imageFile, String outputPath,
                                      String apiKey, String baseUrl) {
        try {
            String boundary = "----GptImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = new URL(baseUrl + "/v1/images/edits");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(120_000);

            try (OutputStream os = conn.getOutputStream()) {
                writeField(os, boundary, "model",   "gpt-image-2");
                writeField(os, boundary, "prompt",  prompt != null ? prompt : "product photo on clean background");
                writeField(os, boundary, "size",    "1024x1024");
                writeField(os, boundary, "quality", "standard");
                writeField(os, boundary, "n",       "1");
                writeFile(os,  boundary, "image",   imageFile);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status < 200 || status >= 300) {
                log.error("GPT-Image edits 失败 ({}): {}", status, respBody);
                return false;
            }

            return saveFromResponse(respBody, outputPath);
        } catch (Exception e) {
            log.error("GPT-Image edits 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 无图片时调用 /v1/images/generations */
    private boolean generateTextOnly(String prompt, String outputPath, String apiKey, String baseUrl) {
        try {
            Map<String, Object> payload = Map.of(
                "model",         "gpt-image-2",
                "prompt",        prompt != null ? prompt : "product photo",
                "size",          "1024x1024",
                "quality",       "standard",
                "output_format", "png",
                "n",             1
            );

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
                log.error("GPT-Image generations 失败 ({}): {}", status, respBody);
                return false;
            }

            return saveFromResponse(respBody, outputPath);
        } catch (Exception e) {
            log.error("GPT-Image generations 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean saveFromResponse(String respBody, String outputPath) throws Exception {
        Map<String, Object> resp = mapper.readValue(respBody, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        if (data == null || data.isEmpty()) {
            log.error("GPT-Image 响应中无 data 字段");
            return false;
        }

        Map<String, Object> item = data.get(0);
        new File(outputPath).getParentFile().mkdirs();

        if (item.containsKey("b64_json")) {
            byte[] imgBytes = Base64.getDecoder().decode((String) item.get("b64_json"));
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
    }

    private void writeField(OutputStream os, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
            + value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFile(OutputStream os, String boundary, String fieldName, File file) throws Exception {
        String filename = file.getName();
        String mime = filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        String header = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + mime + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(Files.readAllBytes(file.toPath()));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
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
