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

    /** 返回所有 key 的列表，前两个优先（稳定 key），其余按顺序追加 */
    private List<String> orderedKeys() {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) return List.of();
        return new java.util.ArrayList<>(keys); // yml 中前两个即为优先 key
    }

    @Override
    public String getId() { return "gpt-image"; }

    @Override
    public String getDisplayName() { return "GPT-Image 2"; }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        return generateMulti(prompt,
                (whiteBgPath != null && !whiteBgPath.isBlank()) ? List.of(whiteBgPath)
                        : (refImagePath != null && !refImagePath.isBlank()) ? List.of(refImagePath) : List.of(),
                null, outputPath, null);
    }

    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }
        String baseUrl = appProperties.getGptImage().getBaseUrl();
        List<File> imageFiles = resolveImageFiles(refImagePaths);
        String size = pickSize(aspect);
        for (String apiKey : orderedKeys()) {
            boolean ok = !imageFiles.isEmpty()
                    ? generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size)
                    : generateTextOnly(prompt, outputPath, apiKey, baseUrl, size);
            if (ok) return true;
            log.warn("GPT-Image key 尾号[{}] 失败，尝试下一个", apiKey.length() > 6 ? apiKey.substring(apiKey.length() - 6) : apiKey);
        }
        log.error("GPT-Image 所有 key 均失败");
        return false;
    }

    private List<File> resolveImageFiles(List<String> paths) {
        List<File> out = new java.util.ArrayList<>();
        if (paths == null) return out;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        return out;
    }

    /** 按 aspect 映射到 OpenAI 支持的 size；接口支持 1024x1024 / 1024x1536 / 1536x1024 / auto */
    private String pickSize(String aspect) {
        if (aspect == null) return "1024x1024";
        return switch (aspect) {
            case "9:16", "portrait" -> "1024x1536";
            case "16:9", "landscape" -> "1536x1024";
            default -> "1024x1024";
        };
    }

    /** 有图片时调用 /v1/images/edits，所有图片以 image[] 形式一并提交 */
    private boolean generateWithImages(String prompt, List<File> imageFiles, String outputPath,
                                       String apiKey, String baseUrl, String size) {
        try {
            String boundary = "----GptImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = new URL(baseUrl + "/v1/images/edits");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(300_000);

            try (OutputStream os = conn.getOutputStream()) {
                writeField(os, boundary, "model",         "gpt-image-2");
                writeField(os, boundary, "prompt",        prompt != null ? prompt : "product photo on clean background");
                writeField(os, boundary, "size",          size);
                writeField(os, boundary, "quality",       "high");
                writeField(os, boundary, "output_format", "jpeg");
                // OpenAI edits 接口接受 image[] 数组：单图字段名 "image"，多图统一用 "image[]"
                String fieldName = imageFiles.size() == 1 ? "image" : "image[]";
                for (File f : imageFiles) writeFile(os, boundary, fieldName, f);
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

    /** 局部重绘：image + mask → /v1/images/edits，失败自动 fallback 到其他 key */
    public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }
        String baseUrl = appProperties.getGptImage().getBaseUrl();
        for (String apiKey : orderedKeys()) {
            boolean ok = doGenerateWithMask(prompt, imageFile, maskFile, outputPath, apiKey, baseUrl);
            if (ok) return true;
            log.warn("GPT-Image inpaint key 尾号[{}] 失败，尝试下一个", apiKey.length() > 6 ? apiKey.substring(apiKey.length() - 6) : apiKey);
        }
        log.error("GPT-Image inpaint 所有 key 均失败");
        return false;
    }

    private boolean doGenerateWithMask(String prompt, File imageFile, File maskFile,
                                       String outputPath, String apiKey, String baseUrl) {
        try {
            String boundary = "----GptImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = new URL(baseUrl + "/v1/images/edits");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(240_000);

            try (OutputStream os = conn.getOutputStream()) {
                writeField(os, boundary, "model",         "gpt-image-2");
                writeField(os, boundary, "prompt",        prompt != null ? prompt : "");
                writeField(os, boundary, "size",          "1024x1024");
                writeField(os, boundary, "quality",       "high");
                writeField(os, boundary, "output_format", "jpeg");
                writeFile(os,  boundary, "image",         imageFile);
                writeFile(os,  boundary, "mask",          maskFile);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();

            if (status < 200 || status >= 300) {
                log.error("GPT-Image inpaint 失败 ({}): {}", status, respBody);
                return false;
            }
            return saveFromResponse(respBody, outputPath);
        } catch (Exception e) {
            log.error("GPT-Image inpaint 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 无图片时调用 /v1/images/generations */
    private boolean generateTextOnly(String prompt, String outputPath, String apiKey, String baseUrl, String size) {
        try {
            Map<String, Object> payload = Map.of(
                "model",         "gpt-image-2",
                "prompt",        prompt != null ? prompt : "product photo",
                "size",          size,
                "quality",       "high",
                "output_format", "jpeg"
            );

            String jsonBody = mapper.writeValueAsString(payload);
            URL url = new URL(baseUrl + "/v1/images/generations");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(240_000);

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
