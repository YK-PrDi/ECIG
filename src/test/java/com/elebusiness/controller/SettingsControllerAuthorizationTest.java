package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.ConfigService;
import com.elebusiness.service.DingTalkService;
import com.elebusiness.service.UserPrefsService;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.workspace.UserStorageService;
import com.elebusiness.service.workspace.UserWorkspaceDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SettingsControllerAuthorizationTest {

    @TempDir
    Path tempDir;

    @Test
    void nonAdminSettingsReadOnlyReturnsPersonalPreferences() {
        ConfigService configService = mock(ConfigService.class);
        SettingsController controller = controller(configService, mock(DingTalkService.class));
        MockHttpSession session = userSession("USER");

        Map<String, Object> settings = controller.getSettings(session);

        assertFalse(settings.containsKey("dingtalk"), "普通用户不能读取全局钉钉配置");
        assertFalse(settings.containsKey("proxy"), "普通用户不能读取全局代理配置");
        assertEquals("", settings.get("customOutputDir"));
        verifyNoInteractions(configService);
    }

    @Test
    void nonAdminCannotWriteGlobalSettingsButCanWriteOwnOutputDir() throws Exception {
        ConfigService configService = mock(ConfigService.class);
        DingTalkService dingTalkService = mock(DingTalkService.class);
        SettingsController controller = controller(configService, dingTalkService);
        MockHttpSession session = userSession("USER");

        ResponseEntity<Map<String, Object>> forbidden = controller.saveSettings(
                Map.of("dingtalk", Map.of("app_key", "x")), session);
        assertEquals(403, forbidden.getStatusCode().value());
        verify(configService, never()).saveDingTalkConfig(org.mockito.ArgumentMatchers.anyMap());
        verify(dingTalkService, never()).invalidateCache();

        Path outputDir = tempDir.resolve("alice-output");
        Files.createDirectories(outputDir);
        ResponseEntity<Map<String, Object>> ok = controller.saveSettings(
                Map.<String, Object>of("customOutputDir", outputDir.toString()), session);
        assertEquals(200, ok.getStatusCode().value());
    }

    private SettingsController controller(ConfigService configService, DingTalkService dingTalkService) {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.resolve("user-data").toString());
        UserStorageService storageService = new UserStorageService(props);
        UserPrefsService userPrefsService = new UserPrefsService(
                new UserWorkspaceDatabaseService(storageService), storageService);
        return new SettingsController(configService, dingTalkService, userPrefsService, new CurrentUserService());
    }

    private MockHttpSession userSession(String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", role));
        return session;
    }
}
