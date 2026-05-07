package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** 启动时将 config.json 中保存的代理设置应用到运行时 */
    @PostConstruct
    public void applyProxyFromConfig() {
        Map<String, String> cfg = loadConfig();
        String host = cfg.getOrDefault("proxy_host", "");
        appProperties.getProxy().setHost(host);
        try {
            appProperties.getProxy().setPort(Integer.parseInt(cfg.getOrDefault("proxy_port", "8086")));
        } catch (NumberFormatException ignored) {}
        if (!host.isBlank()) {
            log.info("代理模式：显式代理 {}:{}", host, appProperties.getProxy().getPort());
        } else {
            log.info("代理模式：跟随系统代理（java.net.useSystemProxies=true）");
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> loadConfig() {
        File configFile = new File(appProperties.getPaths().getConfigFile());
        if (configFile.exists()) {
            try {
                return objectMapper.readValue(configFile, Map.class);
            } catch (Exception e) {
                log.warn("读取 config.json 失败，使用默认配置: {}", e.getMessage());
            }
        }
        Map<String, String> defaults = new HashMap<>();
        defaults.put("sheet_id", appProperties.getDingtalk().getSheetId());
        return defaults;
    }

    public void saveConfig(Map<String, String> config) {
        File configFile = new File(appProperties.getPaths().getConfigFile());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFile, config);
            log.info("配置已保存: {}", config);
        } catch (Exception e) {
            log.error("保存 config.json 失败: {}", e.getMessage(), e);
            throw new RuntimeException("保存配置失败: " + e.getMessage());
        }
    }

    public String getCurrentSheetId() {
        return loadConfig().getOrDefault("sheet_id", appProperties.getDingtalk().getSheetId());
    }

    public void updateSheetId(String sheetId) {
        Map<String, String> cfg = loadConfig();
        cfg.put("sheet_id", sheetId);
        saveConfig(cfg);
    }

    /** 返回钉钉运行时配置（config.json 优先，fallback 到 yml） */
    public Map<String, String> getDingTalkConfig() {
        Map<String, String> cfg = loadConfig();
        AppProperties.DingTalk dt = appProperties.getDingtalk();
        Map<String, String> result = new HashMap<>();
        result.put("app_key",    cfg.getOrDefault("app_key",    nvl(dt.getAppKey())));
        result.put("app_secret", cfg.getOrDefault("app_secret", nvl(dt.getAppSecret())));
        result.put("union_id",   cfg.getOrDefault("union_id",   ""));
        result.put("app_uuid",   cfg.getOrDefault("app_uuid",   nvl(dt.getAppUuid())));
        result.put("sheet_id",   cfg.getOrDefault("sheet_id",   nvl(dt.getSheetId())));
        return result;
    }

    /** 保存钉钉配置（合并到 config.json，只覆盖传入的字段） */
    public void saveDingTalkConfig(Map<String, String> updates) {
        Map<String, String> cfg = loadConfig();
        Set<String> allowed = Set.of("app_key", "app_secret", "union_id", "app_uuid", "sheet_id");
        updates.forEach((k, v) -> { if (allowed.contains(k) && v != null) cfg.put(k, v); });
        saveConfig(cfg);
    }

    /** 返回代理配置（config.json 优先，fallback 到 appProperties） */
    public Map<String, String> getProxyConfig() {
        Map<String, String> cfg = loadConfig();
        Map<String, String> result = new HashMap<>();
        result.put("proxy_host", cfg.getOrDefault("proxy_host", nvl(appProperties.getProxy().getHost())));
        result.put("proxy_port", cfg.getOrDefault("proxy_port", "8086"));
        return result;
    }

    /** 保存代理配置并立即热更新运行时 */
    public void saveProxyConfig(String host, int port) {
        Map<String, String> cfg = loadConfig();
        cfg.put("proxy_host", host != null ? host.trim() : "");
        cfg.put("proxy_port", String.valueOf(port));
        saveConfig(cfg);
        appProperties.getProxy().setHost(host != null ? host.trim() : "");
        appProperties.getProxy().setPort(port);
        log.info("代理配置已更新: {}:{}", host, port);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
