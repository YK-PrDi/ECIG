package com.elebusiness.config;

import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class ApiAuthenticationFilter extends OncePerRequestFilter {

    private final CurrentUserService currentUserService;

    public ApiAuthenticationFilter(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/") || PublicApiPaths.isPublic(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        if (currentUserService.isAuthenticated(session)) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write("{\"success\":false,\"error\":\"请先登录\"}".getBytes(StandardCharsets.UTF_8));
    }
}
