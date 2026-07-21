package com.elebusiness.controller;

import com.elebusiness.service.ConfigService;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigControllerAuthorizationTest {

    @Test
    void nonAdminCannotReadRawGlobalConfig() {
        ConfigController controller = controller(mock(ConfigService.class));

        assertThrows(ResponseStatusException.class, () -> controller.getConfig(userSession("USER")));
    }

    @Test
    void configStatusDoesNotExposeSecretFields() {
        ConfigService configService = mock(ConfigService.class);
        when(configService.getDingTalkConfig()).thenReturn(Map.of(
                "app_key", "key",
                "app_secret", "secret",
                "union_id", "union",
                "app_uuid", "uuid",
                "sheet_id", "sheet"
        ));
        ConfigController controller = controller(configService);

        Map<String, Object> status = controller.getConfigStatus();

        assertEquals(true, status.get("dingtalkConfigured"));
        assertFalse(status.containsKey("app_secret"));
        assertFalse(status.containsKey("app_key"));
    }

    private ConfigController controller(ConfigService configService) {
        return new ConfigController(configService, null, null, null, new CurrentUserService(),
                new com.elebusiness.config.AppProperties());
    }

    private MockHttpSession userSession(String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", role, 1L));
        return session;
    }
}
