package com.elebusiness.controller;

import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.provider.UserProviderCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserProviderCredentialControllerTest {

    @Test
    void currentUserCanUpsertAndListOnlyMaskedCredentialSummaries() {
        UserProviderCredentialService service = mock(UserProviderCredentialService.class);
        UserProviderCredentialController controller = new UserProviderCredentialController(service, new CurrentUserService());
        UserProviderCredentialService.CredentialSummary summary = new UserProviderCredentialService.CredentialSummary(
                10L, 1001L, "liblib", "default", true, LocalDateTime.of(2026, 7, 10, 1, 0),
                LocalDateTime.of(2026, 7, 10, 1, 1), List.of("accessKey", "secretKey"));
        when(service.upsertCredential(1001L, "liblib", "default",
                Map.of("accessKey", "ak-1", "secretKey", "sk-1"), true)).thenReturn(summary);
        when(service.listSummaries(1001L)).thenReturn(List.of(summary));

        ResponseEntity<Map<String, Object>> response = controller.upsertCredential(
                userSession(1001L, "USER"),
                "liblib",
                "default",
                Map.of("payload", Map.of("accessKey", "ak-1", "secretKey", "sk-1"), "enabled", true));
        Map<?, ?> saved = (Map<?, ?>) response.getBody().get("credential");
        Map<String, Object> listed = controller.listCredentials(userSession(1001L, "USER"));
        Map<?, ?> listedCredential = (Map<?, ?>) ((List<?>) listed.get("items")).get(0);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("liblib", saved.get("provider"));
        assertEquals(List.of("accessKey", "secretKey"), saved.get("payloadKeys"));
        assertFalse(saved.containsKey("accessKey"));
        assertFalse(saved.containsKey("secretKey"));
        assertEquals(1, listed.get("total"));
        assertFalse(listedCredential.containsKey("secretKey"));
        verify(service).upsertCredential(1001L, "liblib", "default",
                Map.of("accessKey", "ak-1", "secretKey", "sk-1"), true);
        verify(service).listSummaries(1001L);
    }

    private MockHttpSession userSession(long userId, String role) {
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(userId, "user" + userId, "User " + userId, role, 1L));
        return session;
    }
}
