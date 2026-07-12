package com.elebusiness.controller;

import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class AuthControllerHeartbeatTest {

    @Test
    void heartbeatReturnsAuthenticatedUserAndServerTime() {
        CurrentUserService currentUserService = new CurrentUserService();
        AuthController controller = new AuthController(mock(AuthService.class), currentUserService);
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(7L, "user7", "User 7", "USER"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        Map<String, Object> body = controller.heartbeat(request).getBody();

        assertNotNull(body);
        assertEquals(true, body.get("authenticated"));
        assertNotNull(body.get("serverTime"));
        @SuppressWarnings("unchecked")
        Map<String, Object> user = (Map<String, Object>) body.get("user");
        assertEquals(7L, user.get("id"));
        assertEquals("user7", user.get("username"));
    }

    @Test
    void heartbeatDoesNotCreateAnonymousSession() {
        AuthController controller = new AuthController(mock(AuthService.class), new CurrentUserService());
        MockHttpServletRequest request = new MockHttpServletRequest();

        Map<String, Object> body = controller.heartbeat(request).getBody();

        assertNotNull(body);
        assertEquals(false, body.get("authenticated"));
        assertNotNull(body.get("serverTime"));
        assertNull(request.getSession(false));
    }
}
