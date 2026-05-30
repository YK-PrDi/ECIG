package com.elebusiness.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * /data/categories/** 资源处理 + API 登录拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebMvcConfig.class);

    private final AppProperties appProperties;

    public WebMvcConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
                String uri = req.getRequestURI();
                // 白名单：登录接口本身不拦截
                if (uri.startsWith("/api/auth/")) return true;
                // 只拦截 /api/** 接口，静态资源不拦截
                if (!uri.startsWith("/api/")) return true;
                Object auth = req.getSession(false) != null
                        ? req.getSession(false).getAttribute("authenticated") : null;
                if (Boolean.TRUE.equals(auth)) return true;
                res.setStatus(401);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"未登录\",\"code\":401}");
                return false;
            }
        }).addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
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
            log.info("品类资源: /data/categories/** -> [内置] {}", builtInCatLoc);
        }
    }
}
