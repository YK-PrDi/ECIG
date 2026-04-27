package com.elebusiness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import jakarta.validation.constraints.NotBlank;

@Component
@ConfigurationProperties(prefix = "app.dashscope")
public class DashScopeConfig {
    @NotBlank(message = "DashScope API Key 不能为空")
    private String apiKey;
    private String model = "wan2.7-image-pro";
    private String imageSize = "1024*1024";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getImageSize() { return imageSize; }
    public void setImageSize(String imageSize) { this.imageSize = imageSize; }
}
