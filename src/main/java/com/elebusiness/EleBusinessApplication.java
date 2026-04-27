package com.elebusiness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.awt.Desktop;
import java.net.URI;

/**
 * 主启动类 - 产品图片生成系统
 * 功能：从钉钉多维表读取产品数据，调用 Google Gemini AI 生成产品图片
 * 启动后访问 http://localhost:5020
 */
@SpringBootApplication
@EnableConfigurationProperties
public class EleBusinessApplication {

    public static void main(String[] args) {
        SpringApplication.run(EleBusinessApplication.class, args);
        System.out.println("========================================");
        System.out.println("  产品图片生成系统已启动");
        System.out.println("  访问地址: http://localhost:5020");
        System.out.println("========================================");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI("http://localhost:5020"));
            }
        } catch (Exception ignored) {}
    }
}