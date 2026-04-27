package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigService(AppProperties appProperties) {
        this.appProperties = appProperties;
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
        Map<String, String> config = new HashMap<>();
        config.put("sheet_id", sheetId);
        saveConfig(config);
    }
}
