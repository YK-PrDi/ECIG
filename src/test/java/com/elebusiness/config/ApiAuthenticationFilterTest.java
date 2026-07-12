package com.elebusiness.config;

import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiAuthenticationFilterTest {

    @Test
    void allowsPublicReadOnlyApisWithoutSession() throws ServletException, IOException {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(new CurrentUserService());

        for (String uri : List.of(
                "/api/auth/check",
                "/api/prompts",
                "/api/prompts/search",
                "/api/categories/index",
                "/api/config/status",
                "/api/agents"
        )) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertEquals(200, response.getStatus(), uri);
        }
    }

    @Test
    void blocksProtectedApisWithoutSession() throws ServletException, IOException {
        ApiAuthenticationFilter filter = new ApiAuthenticationFilter(new CurrentUserService());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/custom_generate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
    }
}
