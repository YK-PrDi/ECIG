package com.elebusiness.controller;

import com.elebusiness.config.AppProperties;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.UserPrefsService;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import com.elebusiness.service.workspace.UserStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceControllerIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void feedbackIsStoredInCurrentUsersFileWorkspace() throws Exception {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.resolve("user-data").toString());
        props.getPaths().setOutputDir(tempDir.resolve("global-output").toString());
        UserStorageService storageService = new UserStorageService(props);
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER", 1L));

        ResourceController controller = new ResourceController(
                null, props, null, null, currentUserService, storageService);

        controller.saveFeedback(Map.<String, Object>of(
                "prompt", "prompt-a",
                "imagePath", "/tmp/a.jpg",
                "rating", "good"
        ), session);

        Path userFeedback = storageService.filesRoot(1001L).resolve("feedback.txt");
        assertTrue(Files.exists(userFeedback), "反馈必须写入当前用户文件空间");
        assertTrue(Files.readString(userFeedback).contains("prompt-a"));
        assertFalse(Files.exists(tempDir.resolve("global-output").resolve("feedback.txt")),
                "反馈不能继续混写到全局输出目录");
    }

    @Test
    void galleryListingNormalizesDotSegmentsWithoutCanonicalFileLookup() throws Exception {
        AppProperties props = new AppProperties();
        props.getPaths().setUserDataDir(tempDir.resolve("user-data").toString());
        UserStorageService storageService = new UserStorageService(props);
        CurrentUserService currentUserService = new CurrentUserService();
        MockHttpSession session = new MockHttpSession();
        currentUserService.bind(session, new AuthService.AuthUser(1001L, "alice", "Alice", "USER", 1L));

        Path galleryRoot = storageService.ensureDirectory(storageService.galleryRoot(1001L));
        Path batchDir = Files.createDirectories(galleryRoot.resolve("batch"));
        Files.writeString(batchDir.resolve("result.png"), "image");
        UserPrefsService prefsService = mock(UserPrefsService.class);
        when(prefsService.resolveOutputDir(eq(1001L), anyString())).thenReturn(galleryRoot.toFile());
        ResourceController controller = new ResourceController(
                null, props, prefsService, mock(HistoryService.class), currentUserService, storageService);

        Path normalized = ResourceController.normalizeAbsolutePath(batchDir.resolve(".").toString());
        assertEquals(batchDir.toAbsolutePath().normalize(), normalized);

        ResponseEntity<Map<String, Object>> response = controller.listGallery(batchDir.resolve(".").toString(), session);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1, ((java.util.List<?>) response.getBody().get("items")).size());
    }
}
