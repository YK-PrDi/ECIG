package com.elebusiness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;

/**
 * 主启动类 - 产品图片生成系统
 * 功能：从钉钉多维表读取产品数据，调用 Google Gemini AI 生成产品图片
 * 启动后访问 http://localhost:5020
 */
@SpringBootApplication
@EnableConfigurationProperties
@EnableScheduling
public class EleBusinessApplication {

    private static final Logger log = LoggerFactory.getLogger(EleBusinessApplication.class);

    public static void main(String[] args) {
        // 禁用系统代理，避免服务器环境下代理配置导致 COS 访问异常
        // Windows/macOS 开发环境如需使用系统代理，可临时启用此配置
        // System.setProperty("java.net.useSystemProxies", "true");
        SpringApplication.run(EleBusinessApplication.class, args);

        // 启动诊断日志：记录运行环境关键信息，用于排查"别人机器保存失败"等环境差异问题。
        // 这些信息会同时写入 logs/app.log，别人遇到问题时把日志发回来即可定位。
        logStartupDiagnostics();

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

    /** 打印启动环境诊断信息：工作目录、用户目录、打包标记、关键路径的存在与可写状态。 */
    private static void logStartupDiagnostics() {
        log.info("========== 启动环境诊断 ==========");
        log.info("user.dir (工作目录) = {}", System.getProperty("user.dir"));
        log.info("user.home (用户目录) = {}", System.getProperty("user.home"));
        log.info("os.name = {}", System.getProperty("os.name"));
        log.info("app.packaged (打包标记) = {}", System.getProperty("app.packaged", "false"));
        log.info("app.resources-path = {}", System.getProperty("app.resources-path", "(未设置)"));

        // 检查关键目录的存在 / 可写状态——保存失败大多与这些目录的权限有关
        checkDir(".temp-output (临时归档)", "./.temp-output");
        checkDir("生成结果 (永久输出)", "./生成结果");

        log.info("==================================");
    }

    /** 检查目录是否存在、是否可写；不存在时尝试创建并报告结果。 */
    private static void checkDir(String label, String relPath) {
        File dir = new File(relPath).getAbsoluteFile();
        boolean exists = dir.exists();
        if (!exists) {
            boolean created = dir.mkdirs();
            log.info("[{}] 路径={} 不存在，尝试创建：{}", label, dir.getAbsolutePath(),
                    created ? "成功" : "失败（可能无写权限）");
        } else {
            log.info("[{}] 路径={} 已存在，可写={}", label, dir.getAbsolutePath(), dir.canWrite());
        }
    }
}
