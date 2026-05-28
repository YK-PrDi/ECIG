package com.elebusiness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * /data/categories/** 资源处理：
 * - userDataDir/data/categories/ 优先（用户导入产物）
 * - fallback 到内置 frontend/data/categories/（打包态从 JVM 属性解析路径，源码态用 user.dir）
 * 两个 location 写在同一个 handler，Spring 按顺序查找，第一个命中即返回。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final AppProperties appProperties;

    public WebMvcConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 解析内置 frontend/ 根目录
        // 打包态：electron 通过 -Dspring.web.resources.static-locations=file:<resourcesPath>/frontend/ 注入
        // 源码态：user.dir/frontend/
        String builtInFrontend = null;
        String staticLoc = System.getProperty("spring.web.resources.static-locations");
        if (staticLoc != null && !staticLoc.isBlank()) {
            for (String loc : staticLoc.split(",")) {
                loc = loc.trim();
                if (loc.startsWith("file:")) {
                    builtInFrontend = loc.endsWith("/") ? loc : loc + "/";
                    break;
                }
            }
        }
        if (builtInFrontend == null) {
            builtInFrontend = "file:" + System.getProperty("user.dir").replace('\\', '/') + "/frontend/";
        }
        String builtInCatLoc = builtInFrontend + "data/categories/";

        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir != null && !userDataDir.isBlank()) {
            File catDir = new File(userDataDir, "data/categories");
            try { catDir.mkdirs(); } catch (Exception ignored) {}
            String userLoc = "file:" + catDir.getAbsolutePath().replace('\\', '/') + "/";
            registry.addResourceHandler("/data/categories/**")
                    .addResourceLocations(userLoc, builtInCatLoc);
            log.info("品类资源: /data/categories/** -> [用户] {} , [内置] {}", userLoc, builtInCatLoc);
        } else {
            registry.addResourceHandler("/data/categories/**")
                    .addResourceLocations(builtInCatLoc);
            log.info("品类资源: /data/categories/** -> [内置] ", builtInCatLoc);
        }
    }
}
