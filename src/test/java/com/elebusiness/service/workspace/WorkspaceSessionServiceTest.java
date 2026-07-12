package com.elebusiness.service.workspace;

import com.elebusiness.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSessionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void currentSessionIsScopedByUser() {
        WorkspaceSessionService service = sessionService();

        WorkspaceSessionService.SessionDto user1Default = service.currentSession(1L);
        WorkspaceSessionService.SessionDto user2Default = service.currentSession(2L);

        String user1NewSession = service.createSession(1L, "A 用户会话");
        assertTrue(service.switchSession(1L, user1NewSession));

        assertEquals(user1NewSession, service.currentSession(1L).id());
        assertEquals(user2Default.id(), service.currentSession(2L).id());
        assertEquals("sess_default", user1Default.id());
        assertEquals("sess_default", user2Default.id());
    }

    @Test
    void userCannotDeleteAnotherUsersSession() {
        WorkspaceSessionService service = sessionService();

        String user1Session = service.createSession(1L, "A 用户会话");

        assertFalse(service.deleteSession(2L, user1Session));
        assertTrue(service.switchSession(1L, user1Session));
    }

    private WorkspaceSessionService sessionService() {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.toString());
        UserStorageService storageService = new UserStorageService(props);
        return new WorkspaceSessionService(new UserWorkspaceDatabaseService(storageService));
    }
}
