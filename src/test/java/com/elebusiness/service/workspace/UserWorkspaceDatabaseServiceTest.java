package com.elebusiness.service.workspace;

import com.elebusiness.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserWorkspaceDatabaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsSeparateWorkspaceDatabasesForDifferentUsers() throws Exception {
        UserWorkspaceDatabaseService service = workspaceService();

        try (Connection user1 = service.openConnection(1L);
             Statement st = user1.createStatement()) {
            st.executeUpdate("""
                    insert into conversation_history(session_id, role, content, mode, created_at)
                    values ('sess_a', 'user', 'user-a-message', 'custom', '2026-07-10T00:00:00')
                    """);
        }

        int user1Count;
        try (Connection user1 = service.openConnection(1L);
             Statement st = user1.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from conversation_history")) {
            rs.next();
            user1Count = rs.getInt(1);
        }

        int user2Count;
        try (Connection user2 = service.openConnection(2L);
             Statement st = user2.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from conversation_history")) {
            rs.next();
            user2Count = rs.getInt(1);
        }

        assertEquals(1, user1Count);
        assertEquals(0, user2Count);
        assertTrue(Files.exists(service.databasePath(1L)));
        assertTrue(Files.exists(service.databasePath(2L)));
        assertNotEquals(service.databasePath(1L), service.databasePath(2L));
    }

    private UserWorkspaceDatabaseService workspaceService() {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.toString());
        UserStorageService storageService = new UserStorageService(props);
        return new UserWorkspaceDatabaseService(storageService);
    }
}
