package com.elebusiness.service.agent;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
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

    private List<String> orderedKeys() {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) return List.of();
        return new ArrayList<>(keys);
    }

    private String baseUrlForKey(String apiKey) {
        Map<String, String> overrides = appProperties.getGptImage().getKeyBaseUrls();
        String configured = overrides != null ? overrides.get(apiKey) : null;
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured);
        }
        return trimTrailingSlash(appProperties.getGptImage().getBaseUrl());
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.linapi.net";
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 6) return "***";
        return apiKey.substring(0, 4) + "***";
    }

    @Override
    public String getId() {
        return "gpt-image";
    }

    @Override
    public String getDisplayName() {
        return "GPT-Image 2";
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        // 构建图片列表传给 GPT-Image edits API
        // 注意：GPT-Image 的 generateMulti 只使用 refImagePaths 参数，whiteBgPath 会被忽略
        // 因此需要把参考图和白底图都放进 refImagePaths 列表
        List<String> refs = new ArrayList<>();

        // 优先添加参考图（提供场景、背景、构图参考）
        if (refImagePath != null && !refImagePath.isBlank()) {
            refs.add(refImagePath);
        }

        // 添加白底产品图（提供产品主体特征，确保产品一致性）
        // 即使与参考图相同也添加，因为这代表用户明确要求保留产品特征
        if (whiteBgPath != null && !whiteBgPath.isBlank()) {
            // 只有当白底图与参考图路径不同时才添加，避免重复
            if (refImagePath == null || refImagePath.isBlank() || !whiteBgPath.equals(refImagePath)) {
                refs.add(whiteBgPath);
            }
        }

        return generateMulti(
                prompt,
                refs.isEmpty() ? null : refs,
                null,  // GPT-Image 的 generateMulti 不使用此参数
                outputPath,
                null
        );
    }

    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }

        List<File> imageFiles = resolveImageFiles(refImagePaths);
        String size = pickSize(aspect);
        for (String apiKey : orderedKeys()) {
            String baseUrl = baseUrlForKey(apiKey);
            log.info("GPT-Image 尝试 key [{}], baseUrl={}", maskKey(apiKey), baseUrl);
            boolean ok = !imageFiles.isEmpty()
                    ? generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size)
                    : generateTextOnly(prompt, outputPath, apiKey, baseUrl, size);
            if (ok) return true;
            log.warn("GPT-Image key [{}] 失败，尝试下一个", maskKey(apiKey));
        }

        log.error("GPT-Image 所有 key 均失败");
        return false;
    }

    private String buildSizeHint(String size) {
        if (size == null || size.isBlank()) return "";
        return switch (size) {
            case "1024x1024" -> " Output image must be perfectly square (1:1 aspect ratio, 1024x1024).";
            case "1024x1536" -> " Output image must be portrait (9:16 aspect ratio, 1024x1536).";
            case "1536x1024" -> " Output image must be landscape (16:9 aspect ratio, 1536x1024).";
            case "1024x1360" -> " Output image must be portrait (3:4 aspect ratio, 1024x1360).";
            case "1360x1024" -> " Output image must be landscape (4:3 aspect ratio, 1360x1024).";
            default -> "";
        };
    }

    private List<File> resolveImageFiles(List<String> paths) {
        List<File> out = new ArrayList<>();
        if (paths == null) return out;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        return out;
    }

    private String pickSize(String aspect) {
        if (aspect == null || "auto".equals(aspect)) return "1024x1024";
        return switch (aspect) {
            case "9:16", "portrait" -> "1024x1536";
            case "16:9", "landscape" -> "1536x1024";
            case "1:1" -> "1024x1024";
            case "3:4" -> "1024x1360";
            case "4:3" -> "1360x1024";
            default -> "1024x1024";
        };
    }

    private boolean generateWithImages(String prompt, List<File> imageFiles, String outputPath,
                                       String apiKey, String baseUrl, String size) {
        List<File> tempFiles = new ArrayList<>();
        try {
            List<File> preparedFiles = new ArrayList<>();
            for (File f : imageFiles) {
                File prepared = prepareInputImage(f, size);
                if (prepared != f) tempFiles.add(prepared);
                preparedFiles.add(prepared);
            }

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
                String sizeHint = buildSizeHint(size);
                String finalPrompt = (prompt != null ? prompt : "product photo on clean background") + sizeHint;
                writeField(os, boundary, "model", "gpt-image-2");
                writeField(os, boundary, "prompt", finalPrompt);
                writeField(os, boundary, "size", size);
                writeField(os, boundary, "quality", "medium");
                writeField(os, boundary, "output_format", "jpeg");
                for (File f : preparedFiles) {
                    writeFile(os, boundary, "image[]", f);
                }
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }

            if (status < 200 || status >= 300) {
                log.error("GPT-Image edits 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
        } catch (Exception e) {
            log.error("GPT-Image edits 异常: {}", e.getMessage(), e);
            return false;
        } finally {
            for (File t : tempFiles) { try { t.delete(); } catch (Exception ignored) {} }
        }
    }

    private File prepareInputImage(File src, String size) {
        try {
            int[] target = parseSize(size);
            if (target == null) return src;
            int tw = target[0], th = target[1];
            System.setProperty("java.awt.headless", "true");
            BufferedImage img = ImageIO.read(src);
            if (img == null) return src;
            int sw = img.getWidth(), sh = img.getHeight();
            double srcRatio = (double) sw / sh;
            double tgtRatio = (double) tw / th;
            if (Math.abs(srcRatio - tgtRatio) < 0.02) return src; // already close enough
            BufferedImage canvas = new BufferedImage(tw, th, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = canvas.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((double) tw / sw, (double) th / sh);
            int dw = (int) Math.round(sw * scale);
            int dh = (int) Math.round(sh * scale);
            int dx = (tw - dw) / 2;
            int dy = (th - dh) / 2;
            g.drawImage(img, dx, dy, dw, dh, null);
            g.dispose();
            File tmp = File.createTempFile("gptimg_input_", ".jpg", src.getParentFile());
            ImageIO.write(canvas, "jpeg", tmp);
            log.info("prepareInputImage: {}x{} -> letterbox {}x{} ({})", sw, sh, tw, th, tmp.getName());
            return tmp;
        } catch (Exception e) {
            log.warn("prepareInputImage failed, using original: {}", e.getMessage());
            return src;
        }
    }

    public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath) {
        return generateWithMask(prompt, imageFile, maskFile, outputPath, "auto");
    }

    public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath, String aspect) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }

        String size = pickSize(aspect);
        log.info("GPT-Image inpaint 使用 size={} (aspect={})", size, aspect);
        for (String apiKey : orderedKeys()) {
            String baseUrl = baseUrlForKey(apiKey);
            log.info("GPT-Image inpaint 尝试 key [{}], baseUrl={}", maskKey(apiKey), baseUrl);
            boolean ok = doGenerateWithMask(prompt, imageFile, maskFile, outputPath, apiKey, baseUrl, size);
            if (ok) return true;
            log.warn("GPT-Image inpaint key [{}] 失败，尝试下一个", maskKey(apiKey));
        }

        log.error("GPT-Image inpaint 所有 key 均失败");
        return false;
    }

    private boolean doGenerateWithMask(String prompt, File imageFile, File maskFile,
                                       String outputPath, String apiKey, String baseUrl, String size) {
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
                writeField(os, boundary, "model", "gpt-image-2");
                writeField(os, boundary, "prompt", prompt != null ? prompt : "");
                writeField(os, boundary, "size", size);
                writeField(os, boundary, "quality", "medium");
                writeField(os, boundary, "output_format", "jpeg");
                writeFile(os, boundary, "image", imageFile);
                writeFile(os, boundary, "mask", maskFile);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }

            if (status < 200 || status >= 300) {
                log.error("GPT-Image inpaint 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
        } catch (Exception e) {
            log.error("GPT-Image inpaint 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean generateTextOnly(String prompt, String outputPath, String apiKey, String baseUrl, String size) {
        try {
            Map<String, Object> payload = Map.of(
                    "model", "gpt-image-2",
                    "prompt", prompt != null ? prompt : "product photo",
                    "size", size,
                    "quality", "medium",
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
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                respBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } finally {
                conn.disconnect();
            }

            if (status < 200 || status >= 300) {
                log.error("GPT-Image generations 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
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
        File parent = new File(outputPath).getParentFile();
        if (parent != null) parent.mkdirs();

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

    private void ensureSize(String outputPath, String size) {
        try {
            int[] target = parseSize(size);
            if (target == null) return;
            int tw = target[0], th = target[1];
            File file = new File(outputPath);
            if (!file.exists()) return;
            System.setProperty("java.awt.headless", "true");
            BufferedImage src = ImageIO.read(file);
            if (src == null) {
                log.warn("ensureSize: ImageIO.read returned null for {}", outputPath);
                return;
            }
            int sw = src.getWidth(), sh = src.getHeight();
            log.info("ensureSize: actual={}x{}, target={}x{}, path={}", sw, sh, tw, th, outputPath);
            if (Math.abs(sw - tw) <= tw * 0.05 && Math.abs(sh - th) <= th * 0.05) {
                log.info("ensureSize: already within tolerance, skipping");
                return;
            }
            BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = dst.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.min((double) tw / sw, (double) th / sh);
            int dw = (int) Math.round(sw * scale);
            int dh = (int) Math.round(sh * scale);
            int dx = (tw - dw) / 2;
            int dy = (th - dh) / 2;
            g.drawImage(src, dx, dy, dw, dh, null);
            g.dispose();
            boolean written = ImageIO.write(dst, "jpeg", file);
            if (written) {
                log.info("ensureSize: resized {}x{} -> {}x{} OK", sw, sh, tw, th);
            } else {
                log.warn("ensureSize: ImageIO.write returned false for {}", outputPath);
            }
        } catch (Exception e) {
            log.warn("ensureSize failed for {}: {}", outputPath, e.getMessage(), e);
        }
    }

    private int[] parseSize(String size) {
        if (size == null || size.isBlank()) return null;
        String[] parts = size.split("x");
        if (parts.length != 2) return null;
        try {
            return new int[]{ Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
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

    private boolean downloadUrl(String imgUrl, String outputPath) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(imgUrl).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            File parent = new File(outputPath).getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {
                in.transferTo(fos);
            } finally {
                conn.disconnect();
            }
            log.info("GPT-Image 生成成功 (url) -> {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("GPT-Image 下载图片失败: {}", e.getMessage(), e);
            return false;
        }
    }
}
