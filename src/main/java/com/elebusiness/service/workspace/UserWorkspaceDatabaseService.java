package com.elebusiness.service.workspace;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每用户 SQLite 工作库管理器。
 *
 * 主库负责账号、钱包和流水；用户工作库只保存用户自己的业务数据。
 */
@Service
public class UserWorkspaceDatabaseService {

    private final UserStorageService storageService;
    private final Set<Long> initializedUsers = ConcurrentHashMap.newKeySet();

    public UserWorkspaceDatabaseService(UserStorageService storageService) {
        this.storageService = storageService;
    }

    public Path databasePath(long userId) {
        return storageService.workspaceDatabase(userId);
    }

    public Connection openConnection(long userId) throws SQLException {
        initialize(userId);
        return DriverManager.getConnection(jdbcUrl(databasePath(userId)));
    }

    public void initialize(long userId) {
        if (initializedUsers.contains(userId)) {
            return;
        }
        synchronized (this) {
            if (initializedUsers.contains(userId)) {
                return;
            }
            Path dbPath = databasePath(userId);
            try {
                Files.createDirectories(dbPath.getParent());
            } catch (Exception e) {
                throw new IllegalStateException("无法创建用户工作库目录: " + dbPath.getParent(), e);
            }
            try (Connection conn = DriverManager.getConnection(jdbcUrl(dbPath));
                 Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
                createWorkspaceSessionTable(st);
                createConversationHistoryTable(st);
                createGenerationHistoryTable(st);
                createKaiPinMaterialTable(st);
                createUserPreferencesTable(st);
                initializedUsers.add(userId);
            } catch (SQLException e) {
                throw new IllegalStateException("初始化用户工作库失败: " + dbPath, e);
            }
        }
    }

    private String jdbcUrl(Path dbPath) {
        return "jdbc:sqlite:" + dbPath.toAbsolutePath().normalize();
    }

    private void createWorkspaceSessionTable(Statement st) throws SQLException {
        st.execute("""
                create table if not exists workspace_session (
                    session_id text primary key,
                    name text not null,
                    current integer not null default 0,
                    created_at text not null,
                    last_active_at text not null
                )
                """);
        st.execute("create index if not exists idx_workspace_session_current on workspace_session(current)");
        st.execute("create index if not exists idx_workspace_session_last_active on workspace_session(last_active_at)");
    }

    private void createConversationHistoryTable(Statement st) throws SQLException {
        st.execute("""
                create table if not exists conversation_history (
                    id integer primary key autoincrement,
                    session_id text not null,
                    role text not null,
                    content text not null,
                    mode text,
                    created_at text not null
                )
                """);
        st.execute("create index if not exists idx_conv_session on conversation_history(session_id)");
        st.execute("create index if not exists idx_conv_created on conversation_history(created_at)");
    }

    private void createGenerationHistoryTable(Statement st) throws SQLException {
        st.execute("""
                create table if not exists generation_history (
                    id integer primary key autoincrement,
                    session_id text,
                    mode text not null,
                    prompt text not null,
                    config_json text,
                    agent_id text not null,
                    ref_paths_json text,
                    output_path text,
                    saved_path text,
                    saved integer not null default 0,
                    created_at text not null
                )
                """);
        st.execute("create index if not exists idx_gen_created on generation_history(created_at)");
        st.execute("create index if not exists idx_gen_mode on generation_history(mode)");
        st.execute("create index if not exists idx_gen_session on generation_history(session_id)");
        st.execute("create index if not exists idx_gen_output_path on generation_history(output_path)");
    }

    private void createKaiPinMaterialTable(Statement st) throws SQLException {
        st.execute("""
                create table if not exists kaipin_material (
                    id integer primary key autoincrement,
                    title text,
                    prompt text not null,
                    image_path text not null,
                    original_name text,
                    created_at text not null
                )
                """);
        st.execute("create index if not exists idx_kp_material_created on kaipin_material(created_at)");
    }

    private void createUserPreferencesTable(Statement st) throws SQLException {
        st.execute("""
                create table if not exists user_preferences (
                    pref_key text primary key,
                    pref_value text,
                    updated_at text not null
                )
                """);
    }
}
