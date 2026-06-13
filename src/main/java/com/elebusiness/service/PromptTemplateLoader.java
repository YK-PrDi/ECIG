package com.elebusiness.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 从 classpath 加载 prompt 模板文件（src/main/resources/prompt/*.txt），
 * 读取结果缓存在内存，避免重复 IO。
 */
@Service
public class PromptTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateLoader.class);

    private final ResourceLoader resourceLoader;
    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    public PromptTemplateLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    /**
     * 从 classpath 加载 prompt 模板。
     *
     * @param resourcePath 相对于 classpath 的路径，如 "prompt/image-sku-generation.txt"
     * @param fallback     读取失败时返回的默认值
     * @return 模板内容（不含 BOM / 末尾空白行）
     */
    public String load(String resourcePath, String fallback) {
        return cache.computeIfAbsent(resourcePath, path -> {
            try {
                var resource = resourceLoader.getResource("classpath:" + path);
                if (!resource.exists()) {
                    log.warn("Prompt 文件不存在: {}，使用 fallback", path);
                    return fallback;
                }
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                // 去掉可能的 UTF-8 BOM 和末尾空白
                content = content.replaceFirst("^﻿", "").stripTrailing();
                log.debug("已加载 prompt: {} ({} 字)", path, content.length());
                return content;
            } catch (Exception e) {
                log.error("读取 prompt 文件失败: {} — {}", path, e.getMessage());
                return fallback;
            }
        });
    }
}
