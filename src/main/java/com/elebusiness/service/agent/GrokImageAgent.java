package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Grok 生图智能体（随想 sui-xiang OpenAI 兼容端点）。
 * 模型：grok-imagine；端点与视频共用 app.suixiang-video 的 base-url 与 grok apiKey。
 * - 无参考图：POST {baseUrl}/images/generations（JSON）
 * - 有参考图：POST {baseUrl}/images/edits（multipart，image[] 多文件）
 */
@Component
public class GrokImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(GrokImageAgent.class);
    private static final String MODEL = "grok-imagine";

    private final AppProperties appProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    public GrokImageAgent(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public String getId() {
        return "grok-image";
    }

    @Override
    public String getDisplayName() {
        return "Grok 生图 (grok-imagine)";
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        List<String> refs = new ArrayList<>();
        if (refImagePath != null && !refImagePath.isBlank()) refs.add(refImagePath);
        return generateMulti(prompt, refs, whiteBgPath, outputPath, null);
    }

    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        String apiKey = appProperties.getSuiXiangVideo().getGrok().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Grok 生图 API Key 未配置（SUIXIANG_GROK_VIDEO_API_KEY）");
            return false;
        }
        String baseUrl = appProperties.getSuiXiangVideo().getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://sui-xiang.com/v1";
        baseUrl = baseUrl.replaceAll("/+$", "");

        List<File> imageFiles = new ArrayList<>();
        if (refImagePaths != null) {
            for (String p : refImagePaths) {
                if (p == null || p.isBlank()) continue;
                File f = new File(p);
                if (f.isFile()) imageFiles.add(f);
            }
        }
        String size = pickSize(aspect);
        log.info("Grok 生图: model={}, refs={}, size={}", MODEL, imageFiles.size(), size);
        boolean ok = !imageFiles.isEmpty()
                ? generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size)
                : generateTextOnly(prompt, outputPath, apiKey, baseUrl, size);
        if (!ok) {
            log.error("Grok 生图失败");
        }
        return ok;
    }

    /* ---------- 文生图 ---------- */

    private boolean generateTextOnly(String prompt, String outputPath, String apiKey, String baseUrl, String size) {
        try {
            Map<String, Object> payload = Map.of(
                    "model", MODEL,
                    "prompt", prompt != null && !prompt.isBlank() ? prompt : "product photo",
                    "size", size
            );
            URL url = new URL(baseUrl + "/images/generations");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try (var ignored = GenerationCancellationContext.register(conn::disconnect)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(240_000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(mapper.writeValueAsBytes(payload));
                }
                int status = conn.getResponseCode();
                String respBody = readBody(conn, status);
                if (GenerationCancellationContext.isCancellationRequested()) return false;
                if (status < 200 || status >= 300) {
                    log.error("Grok generations 失败 ({}): {}", status, abbreviate(respBody));
                    return false;
                }
                return saveFromResponse(respBody, outputPath);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.error("Grok generations 异常: {}", e.getMessage());
            return false;
        }
    }

    /* ---------- 参考图编辑 ---------- */

    private boolean generateWithImages(String prompt, List<File> imageFiles,
                                       String outputPath, String apiKey, String baseUrl, String size) {
        try {
            String boundary = "----GrokImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = new URL(baseUrl + "/images/edits");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try (var ignored = GenerationCancellationContext.register(conn::disconnect)) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(300_000);
                try (OutputStream os = conn.getOutputStream()) {
                    writeField(os, boundary, "model", MODEL);
                    writeField(os, boundary, "prompt",
                            (prompt != null && !prompt.isBlank() ? prompt : "product photo") + sizeHint(size));
                    writeField(os, boundary, "size", size);
                    for (File f : imageFiles) {
                        writeFile(os, boundary, "image[]", f);
                    }
                    os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                int status = conn.getResponseCode();
                String respBody = readBody(conn, status);
                if (GenerationCancellationContext.isCancellationRequested()) return false;
                if (status < 200 || status >= 300) {
                    log.error("Grok edits 失败 ({}): {}", status, abbreviate(respBody));
                    return false;
                }
                return saveFromResponse(respBody, outputPath);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            log.error("Grok edits 异常: {}", e.getMessage());
            return false;
        }
    }

    /* ---------- 公共 ---------- */

    private String pickSize(String aspect) {
        if (aspect == null || "auto".equals(aspect) || aspect.isBlank()) return "1024x1024";
        return switch (aspect) {
            case "9:16" -> "1024x1536";
            case "16:9" -> "1536x1024";
            case "3:4" -> "1024x1360";
            case "4:3" -> "1360x1024";
            default -> "1024x1024";
        };
    }

    private String sizeHint(String size) {
        return switch (size) {
            case "1024x1536" -> " Output image must be portrait (9:16 aspect ratio).";
            case "1536x1024" -> " Output image must be landscape (16:9 aspect ratio).";
            case "1024x1360" -> " Output image must be portrait (3:4 aspect ratio).";
            case "1360x1024" -> " Output image must be landscape (4:3 aspect ratio).";
            default -> "";
        };
    }

    @SuppressWarnings("unchecked")
    private boolean saveFromResponse(String respBody, String outputPath) throws Exception {
        Map<String, Object> resp = mapper.readValue(respBody, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        if (data == null || data.isEmpty()) {
            log.error("Grok 响应中无 data 字段: {}", abbreviate(respBody));
            return false;
        }
        Map<String, Object> item = data.get(0);
        File parent = new File(outputPath).getParentFile();
        if (parent != null) parent.mkdirs();

        if (item.containsKey("b64_json")) {
            byte[] imgBytes = Base64.getDecoder().decode((String) item.get("b64_json"));
            File tempFile = new File(outputPath + ".part");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(imgBytes);
            }
            Files.move(tempFile.toPath(), new File(outputPath).toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("Grok 生图成功 (base64) -> {}", outputPath);
            return true;
        }
        if (item.containsKey("url")) {
            return downloadUrl((String) item.get("url"), outputPath);
        }
        log.error("Grok 响应中既无 b64_json 也无 url");
        return false;
    }

    private boolean downloadUrl(String imgUrl, String outputPath) {
        File tempFile = new File(outputPath + ".part");
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(imgUrl).openConnection();
            try (var ignored = GenerationCancellationContext.register(conn::disconnect)) {
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(tempFile)) {
                    in.transferTo(fos);
                }
            } finally {
                conn.disconnect();
            }
            Files.move(tempFile.toPath(), new File(outputPath).toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("Grok 生图成功 (url) -> {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("Grok 图片下载失败: {}", e.getMessage());
            try { tempFile.delete(); } catch (Exception ignored) {}
            return false;
        }
    }

    private String readBody(HttpURLConnection conn, int status) throws Exception {
        try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (is == null) return "";
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    private String abbreviate(String body) {
        if (body == null) return "";
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
