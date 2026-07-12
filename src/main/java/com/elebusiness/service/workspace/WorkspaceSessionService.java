package com.elebusiness.service.workspace;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 用户工作会话服务。
 *
 * 每个用户的会话只存在自己的 workspace.db 中，同名 sessionId 在不同用户之间互不影响。
 */
@Service
public class WorkspaceSessionService {

    public static final String DEFAULT_SESSION_ID = "sess_default";

    private final UserWorkspaceDatabaseService databaseService;

    public WorkspaceSessionService(UserWorkspaceDatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    public SessionDto currentSession(long userId) {
        ensureDefaultSession(userId);
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     select session_id, name, created_at, last_active_at, current
                     from workspace_session
                     where current = 1
                     order by last_active_at desc
                     limit 1
                     """);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return mapSession(rs);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取当前会话失败", e);
        }

        switchSession(userId, DEFAULT_SESSION_ID);
        return currentSession(userId);
    }

    public List<SessionDto> listSessions(long userId) {
        ensureDefaultSession(userId);
        List<SessionDto> sessions = new ArrayList<>();
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     select session_id, name, created_at, last_active_at, current
                     from workspace_session
                     order by last_active_at desc
                     """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                sessions.add(mapSession(rs));
            }
            return sessions;
        } catch (SQLException e) {
            throw new IllegalStateException("读取会话列表失败", e);
        }
    }

    public String createSession(long userId, String name) {
        ensureDefaultSession(userId);
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String safeName = (name == null || name.isBlank()) ? "新会话" : name.trim();
        String now = Instant.now().toString();
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     insert into workspace_session(session_id, name, current, created_at, last_active_at)
                     values (?, ?, 0, ?, ?)
                     """)) {
            ps.setString(1, id);
            ps.setString(2, safeName);
            ps.setString(3, now);
            ps.setString(4, now);
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("创建会话失败", e);
        }
    }

    public boolean switchSession(long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ensureDefaultSession(userId);
        try (Connection conn = databaseService.openConnection(userId)) {
            conn.setAutoCommit(false);
            if (!sessionExists(conn, sessionId)) {
                conn.rollback();
                return false;
            }
            try (PreparedStatement clear = conn.prepareStatement("update workspace_session set current = 0");
                 PreparedStatement set = conn.prepareStatement("""
                         update workspace_session
                         set current = 1, last_active_at = ?
                         where session_id = ?
                         """)) {
                clear.executeUpdate();
                set.setString(1, Instant.now().toString());
                set.setString(2, sessionId);
                set.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("切换会话失败", e);
        }
    }

    public boolean deleteSession(long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ensureDefaultSession(userId);
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement find = conn.prepareStatement("select current from workspace_session where session_id = ?")) {
            find.setString(1, sessionId);
            try (ResultSet rs = find.executeQuery()) {
                if (!rs.next() || rs.getInt("current") == 1) {
                    return false;
                }
            }
            try (PreparedStatement delete = conn.prepareStatement("delete from workspace_session where session_id = ?")) {
                delete.setString(1, sessionId);
                return delete.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("删除会话失败", e);
        }
    }

    public boolean renameSession(long userId, String sessionId, String newName) {
        if (sessionId == null || sessionId.isBlank() || newName == null || newName.isBlank()) {
            return false;
        }
        ensureDefaultSession(userId);
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("update workspace_session set name = ? where session_id = ?")) {
            ps.setString(1, newName.trim());
            ps.setString(2, sessionId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("重命名会话失败", e);
        }
    }

    private void ensureDefaultSession(long userId) {
        databaseService.initialize(userId);
        try (Connection conn = databaseService.openConnection(userId)) {
            if (!sessionExists(conn, DEFAULT_SESSION_ID)) {
                String now = Instant.now().toString();
                try (PreparedStatement ps = conn.prepareStatement("""
                        insert into workspace_session(session_id, name, current, created_at, last_active_at)
                        values (?, '默认会话', 1, ?, ?)
                        """)) {
                    ps.setString(1, DEFAULT_SESSION_ID);
                    ps.setString(2, now);
                    ps.setString(3, now);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement countCurrent = conn.prepareStatement("select count(*) from workspace_session where current = 1");
                 ResultSet rs = countCurrent.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 0) {
                    try (PreparedStatement ps = conn.prepareStatement("update workspace_session set current = 1 where session_id = ?")) {
                        ps.setString(1, DEFAULT_SESSION_ID);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("初始化默认会话失败", e);
        }
    }

    private boolean sessionExists(Connection conn, String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("select 1 from workspace_session where session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private SessionDto mapSession(ResultSet rs) throws SQLException {
        return new SessionDto(
                rs.getString("session_id"),
                rs.getString("name"),
                rs.getString("created_at"),
                rs.getString("last_active_at"),
                rs.getInt("current") == 1
        );
    }

    public record SessionDto(String id, String name, String createdAt, String lastActiveAt, boolean current) {
    }
}
