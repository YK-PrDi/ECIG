package com.elebusiness.service.workspace;

import com.elebusiness.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 用户文件空间解析服务。
 *
 * 多用户隔离采用“每个用户一个工作目录”的边界：
 * user-data/users/{userId}/workspace.db
 * user-data/users/{userId}/files/...
 */
@Service
public class UserStorageService {

    private final AppProperties appProperties;

    public UserStorageService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public Path userRoot(long userId) {
        validateUserId(userId);
        return usersRoot().resolve(String.valueOf(userId)).normalize();
    }

    public Path workspaceDatabase(long userId) {
        return userRoot(userId).resolve("workspace.db").normalize();
    }

    public Path filesRoot(long userId) {
        return userRoot(userId).resolve("files").normalize();
    }

    public Path tempOutputRoot(long userId) {
        return filesRoot(userId).resolve("temp-output").normalize();
    }

    public Path historyRefsRoot(long userId) {
        return filesRoot(userId).resolve("history-refs").normalize();
    }

    public Path kaipinMaterialsRoot(long userId) {
        return filesRoot(userId).resolve("kaipin-materials").normalize();
    }

    public Path galleryRoot(long userId) {
        return filesRoot(userId).resolve("gallery").normalize();
    }

    public Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建用户目录: " + path, e);
        }
    }

    public boolean isInsideUserFiles(long userId, Path candidate) {
        Path root = filesRoot(userId).toAbsolutePath().normalize();
        Path value = candidate.toAbsolutePath().normalize();
        return value.startsWith(root);
    }

    public Path usersRoot() {
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) {
            userDataDir = ".";
        }
        return Paths.get(userDataDir).toAbsolutePath().normalize().resolve("users").normalize();
    }

    private void validateUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("用户 ID 必须大于 0");
        }
    }
}
