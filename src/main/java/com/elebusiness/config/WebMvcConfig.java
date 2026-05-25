package com.elebusiness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * 让用户在 ${userDataDir}/data/categories/ 下覆盖打包态只读的 frontend/data/categories/。
 * 用户用 📥 导入 xlsx 后，新生成的品类 .js 会落到这个可写目录，前端按
 *   /data/categories/<slug>.js
 * 请求时优先命中这里，再 fallback 到 frontend/static-locations。
 *
 * Order：addResourceLocations 注册顺序即查找顺序，第一个命中即返；userDataDir 在前 = 用户覆盖优先。
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
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) return;

        File catDir = new File(userDataDir, "data/categories");
        if (!catDir.exists()) {
            // 不主动创建：导入工具第一次跑时会建；这里建只是为了 mkdirs，不影响 handler 注册
            try { catDir.mkdirs(); } catch (Exception ignored) {}
        }

        // 必须以斜杠结尾，Spring 才会把它当目录处理
        String location = "file:" + catDir.getAbsolutePath().replace('\\', '/') + "/";
        registry.addResourceHandler("/data/categories/**")
                .addResourceLocations(location);
        log.info("用户覆盖目录已挂载: /data/categories/** -> {}", location);
    }
}
