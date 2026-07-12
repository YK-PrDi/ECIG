package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationHistory;
import com.elebusiness.service.workspace.UserStorageService;
import com.elebusiness.service.workspace.UserWorkspaceDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryServiceWorkspaceTest {

    @TempDir
    Path tempDir;

    @Test
    void generationHistoryIsStoredInEachUsersWorkspaceDatabase() {
        HistoryService service = historyService();

        service.recordGeneration(1L, "sess_same", "custom", "prompt-a",
                "liblib-lora", List.<String>of(), "/tmp/a", "{\"a\":1}");

        HistoryService.PageResult<GenerationHistory> user1 = service.listGenerations(
                1L, "sess_same", null, 0, 20);
        HistoryService.PageResult<GenerationHistory> user2 = service.listGenerations(
                2L, "sess_same", null, 0, 20);

        assertEquals(1, user1.totalElements());
        assertEquals(0, user2.totalElements());
        assertEquals("prompt-a", user1.items().get(0).getPrompt());
        assertTrue(user2.items().isEmpty());
    }

    private HistoryService historyService() {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.toString());
        UserStorageService storageService = new UserStorageService(props);
        UserWorkspaceDatabaseService databaseService = new UserWorkspaceDatabaseService(storageService);
        return new HistoryService(null, props, databaseService, storageService);
    }
}
