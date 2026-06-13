package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Session 管理服务。
 *
 * 将 SESSION_ID 从浏览器 localStorage 迁移到服务端管理，
 * 实现跨浏览器会话的历史记录共享。
 *
 * 配置存储在 {userDataDir}/user-session.json 文件中。
 */
@Slf4j
@Service
public class SessionService {

    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    private File sessionFile;
    private String currentSessionId = "sess_default";
    private Map<String, SessionInfo> sessions = new LinkedHashMap<>();

    // 用于同步文件读写
    private final Object lock = new Object();

    public SessionService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        this.objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void init() {
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) {
            userDataDir = ".";
        }
        this.sessionFile = new File(userDataDir, "user-session.json");
        loadOrCreate();
    }

    /**
     * 获取当前 session ID
     */
    public String getCurrentSessionId() {
        synchronized (lock) {
            return currentSessionId;
        }
    }

    /**
     * 设置当前 session ID
     */
    public void setCurrentSessionId(String sessionId) {
        synchronized (lock) {
            this.currentSessionId = sessionId;
            sessions.computeIfAbsent(sessionId, k -> {
                SessionInfo info = new SessionInfo();
                info.setName("会话 " + k.substring(Math.min(5, k.length())));
                info.setCreatedAt(Instant.now());
                return info;
            });
            sessions.get(sessionId).setLastActiveAt(Instant.now());
            save();
        }
    }

    /**
     * 列出所有 session
     */
    public List<SessionInfo> listSessions() {
        synchronized (lock) {
            return new ArrayList<>(sessions.values());
        }
    }

    /**
     * 获取 session 列表（带 ID）
     */
    public List<Map<String, Object>> listSessionsWithId() {
        synchronized (lock) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", entry.getKey());
                item.put("name", entry.getValue().getName());
                item.put("createdAt", entry.getValue().getCreatedAt());
                item.put("lastActiveAt", entry.getValue().getLastActiveAt());
                item.put("isCurrent", entry.getKey().equals(currentSessionId));
                result.add(item);
            }
            return result;
        }
    }

    /**
     * 创建新 session
     */
    public String createSession(String name) {
        synchronized (lock) {
            String id = "sess_" + UUID.randomUUID().toString().substring(0, 8);
            SessionInfo info = new SessionInfo();
            info.setName(name != null && !name.isBlank() ? name : "新会话");
            info.setCreatedAt(Instant.now());
            info.setLastActiveAt(Instant.now());
            sessions.put(id, info);
            save();
            log.info("创建新 session: {} ({})", id, info.getName());
            return id;
        }
    }

    /**
     * 删除 session
     */
    public boolean deleteSession(String sessionId) {
        synchronized (lock) {
            if (sessionId.equals(currentSessionId)) {
                log.warn("不能删除当前激活的 session: {}", sessionId);
                return false;
            }
            if (sessions.remove(sessionId) != null) {
                save();
                log.info("删除 session: {}", sessionId);
                return true;
            }
            return false;
        }
    }

    /**
     * 重命名 session
     */
    public boolean renameSession(String sessionId, String newName) {
        synchronized (lock) {
            SessionInfo info = sessions.get(sessionId);
            if (info != null) {
                info.setName(newName);
                save();
                return true;
            }
            return false;
        }
    }

    /**
     * 加载或创建 session 配置
     */
    private void loadOrCreate() {
        synchronized (lock) {
            if (sessionFile.exists()) {
                try {
                    SessionConfig config = objectMapper.readValue(sessionFile, SessionConfig.class);
                    if (config != null) {
                        this.currentSessionId = config.getCurrentSessionId() != null
                            ? config.getCurrentSessionId()
                            : "sess_default";
                        this.sessions = config.getSessions() != null
                            ? config.getSessions()
                            : new LinkedHashMap<>();
                        log.info("加载 session 配置成功，当前 session: {}", currentSessionId);
                    }
                } catch (IOException e) {
                    log.warn("读取 session 配置失败，将使用默认值并覆盖损坏文件: {}", e.getMessage());
                    try { sessionFile.delete(); } catch (Exception ignored) {}
                    createDefaultSession();
                    save(); // 立即写回干净默认值，避免下次启动重复报错
                }
            } else {
                createDefaultSession();
            }
        }
    }

    /**
     * 创建默认 session
     */
    private void createDefaultSession() {
        this.currentSessionId = "sess_default";
        this.sessions = new LinkedHashMap<>();

        SessionInfo defaultInfo = new SessionInfo();
        defaultInfo.setName("默认会话");
        defaultInfo.setCreatedAt(Instant.now());
        defaultInfo.setLastActiveAt(Instant.now());
        sessions.put(currentSessionId, defaultInfo);

        save();
        log.info("创建默认 session: sess_default");
    }

    /**
     * 保存 session 配置到文件
     */
    private void save() {
        try {
            SessionConfig config = new SessionConfig();
            config.setCurrentSessionId(currentSessionId);
            config.setSessions(sessions);

            // 确保父目录存在
            File parentDir = sessionFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            objectMapper.writeValue(sessionFile, config);
            log.debug("保存 session 配置成功");
        } catch (IOException e) {
            log.error("保存 session 配置失败: {}", e.getMessage());
        }
    }

    // ── 内部数据类 ──────────────────────────────────────────────────

    @Data
    public static class SessionConfig {
        private String currentSessionId;
        private Map<String, SessionInfo> sessions;
    }

    @Data
    public static class SessionInfo {
        private String name;
        private Instant createdAt;
        private Instant lastActiveAt;
    }
}
