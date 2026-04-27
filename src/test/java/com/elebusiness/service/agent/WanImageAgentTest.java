package com.elebusiness.service.agent;

import com.elebusiness.config.DashScopeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WanImageAgentTest {

    @Autowired
    private WanImageAgent wanImageAgent;

    @Autowired
    private DashScopeConfig dashScopeConfig;

    @Test
    void testApiKeyLoaded() {
        assertNotNull(dashScopeConfig.getApiKey(), "API Key 不能为空");
        assertTrue(dashScopeConfig.getApiKey().startsWith("sk-"), "API Key 格式异常");
    }

    @Test
    void testGenerateUrl() {
        String imageUrl = wanImageAgent.generateUrl("一只橘猫坐在窗边，阳光照进来，写实风格");

        assertNotNull(imageUrl, "返回的图片URL不能为空");
        assertTrue(imageUrl.startsWith("http"), "图片URL格式异常: " + imageUrl);
        System.out.println("生成图片URL: " + imageUrl);
    }

    @Test
    void testGenerateToFile() throws IOException {
        File tempFile = File.createTempFile("wan_test_", ".jpg");
        tempFile.deleteOnExit();

        boolean success = wanImageAgent.generate("简洁的白色背景，一杯咖啡", null, null, tempFile.getAbsolutePath());

        assertTrue(success, "图片生成应成功");
        assertTrue(tempFile.length() > 0, "输出文件不应为空");
        System.out.println("图片已保存: " + tempFile.getAbsolutePath() + " (" + tempFile.length() + " bytes)");
    }
}
