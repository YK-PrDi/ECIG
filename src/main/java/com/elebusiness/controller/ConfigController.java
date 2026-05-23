package com.elebusiness.controller;

import com.elebusiness.service.ConfigService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.ImageGenerationService;
import com.elebusiness.service.PromptService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置 / 元数据接口：首页跳转、提示词树、智能体列表、钉钉与代理配置读写。
 * 从 ApiController 拆出（A.1 重构），业务逻辑零变动。
 */
@RestController
public class ConfigController {

    private final ConfigService configService;
    private final DingTalkService dingTalkService;
    private final ImageGenerationService imageGenerationService;
    private final PromptService promptService;

    public ConfigController(ConfigService configService, DingTalkService dingTalkService,
                            ImageGenerationService imageGenerationService, PromptService promptService) {
        this.configService = configService;
        this.dingTalkService = dingTalkService;
        this.imageGenerationService = imageGenerationService;
        this.promptService = promptService;
    }

    @GetMapping("/api/prompts")
    public List<Map<String, Object>> getPrompts() {
        return promptService.getTree();
    }

    @GetMapping("/api/agents")
    public List<Map<String, String>> listAgents() {
        return imageGenerationService.listAgents();
    }

    @GetMapping("/")
    public ResponseEntity<Void> index() {
        return ResponseEntity.status(302).header(HttpHeaders.LOCATION, "/index.html").build();
    }

    @GetMapping("/api/config")
    public Map<String, String> getConfig() {
        Map<String, String> result = new HashMap<>(configService.getDingTalkConfig());
        result.putAll(configService.getProxyConfig());
        return result;
    }

    @PostMapping("/api/config")
    public Map<String, Object> updateConfig(@RequestBody Map<String, String> body) {
        if (body == null || body.isEmpty()) {
            return Map.of("success", false, "error", "请求体为空");
        }
        configService.saveDingTalkConfig(body);
        // 代理配置（可选字段）
        if (body.containsKey("proxy_host")) {
            int port = 7890;
            try {
                port = Integer.parseInt(body.getOrDefault("proxy_port", "7890"));
            } catch (NumberFormatException e) {
                // 用户输入了非数字端口，记日志而不是静默吞掉，方便排查（B 阶段审查 #14）
                org.slf4j.LoggerFactory.getLogger(ConfigController.class)
                        .warn("proxy_port 非数字，回退默认 7890：{}", body.get("proxy_port"));
            }
            configService.saveProxyConfig(body.get("proxy_host"), port);
        }
        dingTalkService.invalidateCache();
        return Map.of("success", true);
    }
}
