package com.elebusiness.service.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class CurrentUserService {

    public static final String AUTHENTICATED = "authenticated";
    public static final String USER_ID = "userId";
    public static final String USERNAME = "username";
    public static final String DISPLAY_NAME = "displayName";
    public static final String ROLE = "role";

    public void bind(HttpSession session, AuthService.AuthUser user) {
        session.setAttribute(AUTHENTICATED, true);
        session.setAttribute(USER_ID, user.id());
        session.setAttribute(USERNAME, user.username());
        session.setAttribute(DISPLAY_NAME, user.displayName());
        session.setAttribute(ROLE, user.role());
    }

    public boolean isAuthenticated(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(AUTHENTICATED))
                && session.getAttribute(USER_ID) instanceof Long;
    }

    public Optional<AuthService.AuthUser> optional(HttpSession session) {
        if (!isAuthenticated(session)) {
            return Optional.empty();
        }
        return Optional.of(new AuthService.AuthUser(
                (Long) session.getAttribute(USER_ID),
                stringAttr(session, USERNAME),
                stringAttr(session, DISPLAY_NAME),
                stringAttr(session, ROLE)
        ));
    }

    public AuthService.AuthUser require(HttpSession session) {
        return optional(session).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }

    public long requireUserId(HttpSession session) {
        return require(session).id();
    }

    public AuthService.AuthUser requireAdmin(HttpSession session) {
        AuthService.AuthUser user = require(session);
        if (!"ADMIN".equalsIgnoreCase(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return user;
    }

    public Map<String, Object> toMap(AuthService.AuthUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.id());
        map.put("username", user.username());
        map.put("displayName", user.displayName());
        map.put("role", user.role());
        return map;
    }

    private String stringAttr(HttpSession session, String key) {
        Object value = session.getAttribute(key);
        return value == null ? "" : String.valueOf(value);
    }
}
