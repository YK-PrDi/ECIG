package com.elebusiness.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户偏好配置：./user-prefs.json
 * 跟 config.json（钉钉/代理）独立 — 钉钉/代理是"应用配置"（往往一次性配好），
 * user-prefs 是"偏好配置"（用户随时可能改，比如换文件保存位置）。
 *
 * 当前字段：
 * - customOutputDir: 用户自选的图片保存位置；空 / 不可写时 ResourceController fallback 到默认 output-dir
 */
@Service
public class UserPrefsService {

    private static final Logger log = LoggerFactory.getLogger(UserPrefsService.class);
    private static final String FILE_NAME = "user-prefs.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Map<String, Object> cache;

    private File prefsFile() {
        // 用 user.dir（项目根 / electron 启动目录），跟 config.json 同级
        return new File(System.getProperty("user.dir"), FILE_NAME);
    }

    @SuppressWarnings("unchecked")
    private synchronized Map<String, Object> read() {
        if (cache != null) return cache;
        File f = prefsFile();
        if (!f.exists()) {
            cache = new HashMap<>();
            return cache;
        }
        try {
            cache = objectMapper.readValue(f, Map.class);
            if (cache == null) cache = new HashMap<>();
        } catch (IOException e) {
            log.warn("user-prefs 读取失败，重置: {}", e.getMessage());
            cache = new HashMap<>();
        }
        return cache;
    }

    private synchronized void write(Map<String, Object> data) {
        cache = data;
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(prefsFile(), data);
        } catch (IOException e) {
            log.error("user-prefs 写入失败: {}", e.getMessage(), e);
        }
    }

    /** 用户自选的图片保存位置，空字符串表示未配置（caller fallback 到默认）。 */
    public String getCustomOutputDir() {
        Object v = read().get("customOutputDir");
        return v == null ? "" : String.valueOf(v).trim();
    }

    public void setCustomOutputDir(String path) {
        Map<String, Object> data = new HashMap<>(read());
        if (path == null || path.isBlank()) {
            data.remove("customOutputDir");
        } else {
            data.put("customOutputDir", path.trim());
        }
        write(data);
    }

    /**
     * 校验目录可用：存在且可写；不存在时尝试创建。
     * 返回 null = 校验通过；非 null = 错误信息（给前端 alert）。
     */
    public String validateDir(String path) {
        if (path == null || path.isBlank()) return null; // 空 = 用默认，不算错
        try {
            Path p = Paths.get(path).toAbsolutePath().normalize();
            File f = p.toFile();
            if (!f.exists()) {
                if (!f.mkdirs()) return "无法创建目录：" + p;
            }
            if (!f.isDirectory()) return "路径不是目录：" + p;
            if (!f.canWrite()) return "目录不可写：" + p;
            // 进一步：尝试在目录内创建临时文件验证写权限
            File probe = new File(f, ".write-probe-" + System.nanoTime());
            try {
                if (!probe.createNewFile()) return "目录写测试失败：" + p;
            } finally {
                probe.delete();
            }
            return null;
        } catch (Exception e) {
            return "路径无效：" + e.getMessage();
        }
    }


    /** 解析最终生效的输出目录：用户自选（验证通过）→ 否则 fallback 到 default。 */
    public File resolveOutputDir(String defaultDir) {
        String custom = getCustomOutputDir();
        if (custom == null || custom.isBlank()) {
            return new File(defaultDir);
        }
        // 校验失败时降级，不阻断生图主流程（A2 fallback 策略）
        String err = validateDir(custom);
        if (err != null) {
            log.warn("customOutputDir 不可用 [{}]，降级到默认目录 [{}]: {}", custom, defaultDir, err);
            return new File(defaultDir);
        }
        return new File(custom);
    }
}
