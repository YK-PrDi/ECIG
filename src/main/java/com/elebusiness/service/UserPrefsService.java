package com.elebusiness.service;

import com.elebusiness.service.workspace.UserStorageService;
import com.elebusiness.service.workspace.UserWorkspaceDatabaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;

@Service
public class UserPrefsService {

    private static final Logger log = LoggerFactory.getLogger(UserPrefsService.class);
    private static final long LEGACY_ADMIN_USER_ID = 1L;
    private static final String CUSTOM_OUTPUT_DIR = "customOutputDir";

    private final UserWorkspaceDatabaseService databaseService;
    private final UserStorageService storageService;

    public UserPrefsService(UserWorkspaceDatabaseService databaseService, UserStorageService storageService) {
        this.databaseService = databaseService;
        this.storageService = storageService;
    }

    public String getCustomOutputDir() {
        return getCustomOutputDir(LEGACY_ADMIN_USER_ID);
    }

    public String getCustomOutputDir(long userId) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("select pref_value from user_preferences where pref_key = ?")) {
            ps.setString(1, CUSTOM_OUTPUT_DIR);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (Exception e) {
            log.warn("读取用户偏好失败: {}", e.getMessage());
            return "";
        }
    }

    public void setCustomOutputDir(String path) {
        setCustomOutputDir(LEGACY_ADMIN_USER_ID, path);
    }

    public void setCustomOutputDir(long userId, String path) {
        try (Connection conn = databaseService.openConnection(userId)) {
            if (path == null || path.isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement("delete from user_preferences where pref_key = ?")) {
                    ps.setString(1, CUSTOM_OUTPUT_DIR);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    insert into user_preferences(pref_key, pref_value, updated_at)
                    values (?, ?, ?)
                    on conflict(pref_key) do update set pref_value = excluded.pref_value, updated_at = excluded.updated_at
                    """)) {
                ps.setString(1, CUSTOM_OUTPUT_DIR);
                ps.setString(2, path.trim());
                ps.setString(3, LocalDateTime.now().toString());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存用户偏好失败", e);
        }
    }

    public String validateDir(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            Path p = Paths.get(path).toAbsolutePath().normalize();
            File f = p.toFile();
            if (!f.exists()) {
                if (!f.mkdirs()) return "无法创建目录：" + p;
            }
            if (!f.isDirectory()) return "路径不是目录：" + p;
            if (!f.canWrite()) return "目录不可写：" + p;
            File probe = new File(f, ".write-probe-" + System.nanoTime());
            try {
                if (!probe.createNewFile()) return "目录写测试失败：" + p;
            } finally {
                probe.delete();
            }
            return null;
        } catch (Exception e) {
            return "路径无效：" + e.getMessage();
        }
    }

    public File resolveOutputDir(String defaultDir) {
        return resolveOutputDir(LEGACY_ADMIN_USER_ID, defaultDir);
    }

    public File resolveOutputDir(long userId, String ignoredDefaultDir) {
        String custom = getCustomOutputDir(userId);
        if (custom == null || custom.isBlank()) {
            return storageService.ensureDirectory(storageService.galleryRoot(userId)).toFile();
        }
        String err = validateDir(custom);
        if (err != null) {
            log.warn("customOutputDir 不可用 [{}]，降级到用户默认图库: {}", custom, err);
            return storageService.ensureDirectory(storageService.galleryRoot(userId)).toFile();
        }
        return new File(custom);
    }
}
