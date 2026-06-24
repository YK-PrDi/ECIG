package com.elebusiness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Civitai API 配置类
 * 用于配置 Civitai 生图服务和 LoRA 预设
 */
@Component
@ConfigurationProperties(prefix = "app.civitai")
public class CivitaiConfig {

    private String apiKey;
    private String model = "sdxl";  // 默认使用 Stable Diffusion XL
    private int maxPollSeconds = 300;  // 轮询超时时间（秒）
    private Map<String, List<LoraResource>> loraPresets = new HashMap<>();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxPollSeconds() {
        return maxPollSeconds;
    }

    public void setMaxPollSeconds(int maxPollSeconds) {
        this.maxPollSeconds = maxPollSeconds;
    }

    public Map<String, List<LoraResource>> getLoraPresets() {
        return loraPresets;
    }

    public void setLoraPresets(Map<String, List<LoraResource>> loraPresets) {
        this.loraPresets = loraPresets;
    }

    /**
     * 根据预设名称获取 LoRA 列表
     */
    public List<LoraResource> getLorasByPreset(String preset) {
        return loraPresets.getOrDefault(preset, new ArrayList<>());
    }

    /**
     * LoRA 资源配置
     */
    public static class LoraResource {
        private Integer id;        // Civitai LoRA ID
        private String name;       // 描述名称（方便管理员识别）
        private Double weight = 0.8;  // 权重（0.1-1.0）

        public Integer getId() {
            return id;
        }

        public void setId(Integer id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Double getWeight() {
            return weight;
        }

        public void setWeight(Double weight) {
            this.weight = weight;
        }
    }
}
