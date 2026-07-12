package com.elebusiness.service;

import com.elebusiness.model.entity.KaiPinMaterial;
import com.elebusiness.service.workspace.UserStorageService;
import com.elebusiness.service.workspace.UserWorkspaceDatabaseService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class KaiPinMaterialService {

    private static final long LEGACY_ADMIN_USER_ID = 1L;

    private final UserWorkspaceDatabaseService databaseService;
    private final UserStorageService storageService;

    public KaiPinMaterialService(UserWorkspaceDatabaseService databaseService, UserStorageService storageService) {
        this.databaseService = databaseService;
        this.storageService = storageService;
    }

    public KaiPinMaterial save(MultipartFile image, String title, String prompt) throws Exception {
        return save(LEGACY_ADMIN_USER_ID, image, title, prompt);
    }

    public KaiPinMaterial save(long userId, MultipartFile image, String title, String prompt) throws Exception {
        if (image == null || image.isEmpty()) throw new IllegalArgumentException("请先上传素材图片");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请先生成或填写素材提示词");

        Path dir = storageService.ensureDirectory(storageService.kaipinMaterialsRoot(userId));
        String original = Optional.ofNullable(image.getOriginalFilename()).orElse("material.jpg");
        String ext = suffix(original);
        String name = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;
        Path target = dir.resolve(name).toAbsolutePath().normalize();

        try (InputStream is = image.getInputStream()) {
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String now = LocalDateTime.now().toString();
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     insert into kaipin_material(title, prompt, image_path, original_name, created_at)
                     values (?, ?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cleanTitle(title, original));
            ps.setString(2, prompt.trim());
            ps.setString(3, target.toString());
            ps.setString(4, original);
            ps.setString(5, now);
            ps.executeUpdate();

            KaiPinMaterial item = new KaiPinMaterial();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) item.setId(keys.getLong(1));
            }
            item.setTitle(cleanTitle(title, original));
            item.setPrompt(prompt.trim());
            item.setImagePath(target.toString());
            item.setOriginalName(original);
            item.setCreatedAt(LocalDateTime.parse(now));
            return item;
        }
    }

    public List<KaiPinMaterial> list(int limit) {
        return list(LEGACY_ADMIN_USER_ID, limit);
    }

    public List<KaiPinMaterial> list(long userId, int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<KaiPinMaterial> items = new ArrayList<>();
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     select id, title, prompt, image_path, original_name, created_at
                     from kaipin_material
                     order by created_at desc
                     limit ?
                     """)) {
            ps.setInt(1, safeLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(map(rs));
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException("读取开品素材失败", e);
        }
    }

    public List<KaiPinMaterial> findAllByIds(List<Long> ids) {
        return findAllByIds(LEGACY_ADMIN_USER_ID, ids);
    }

    public List<KaiPinMaterial> findAllByIds(long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<KaiPinMaterial> items = new ArrayList<>();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        String sql = """
                select id, title, prompt, image_path, original_name, created_at
                from kaipin_material
                where id in (
                """ + placeholders + ")";
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(map(rs));
            }
            return items;
        } catch (SQLException e) {
            throw new IllegalStateException("读取开品素材失败", e);
        }
    }

    public void delete(Long id) {
        delete(LEGACY_ADMIN_USER_ID, id);
    }

    public void delete(long userId, Long id) {
        if (id == null) return;
        KaiPinMaterial item = findAllByIds(userId, List.of(id)).stream().findFirst().orElse(null);
        if (item == null) return;
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("delete from kaipin_material where id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("删除开品素材失败", e);
        }
        try {
            Path image = Path.of(item.getImagePath()).toAbsolutePath().normalize();
            Path root = storageService.kaipinMaterialsRoot(userId).toAbsolutePath().normalize();
            if (image.startsWith(root) && Files.exists(image)) {
                Files.deleteIfExists(image);
            }
        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> toDto(KaiPinMaterial item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.getId());
        dto.put("title", item.getTitle());
        dto.put("prompt", item.getPrompt());
        dto.put("imagePath", item.getImagePath());
        dto.put("originalName", item.getOriginalName());
        dto.put("createdAt", item.getCreatedAt() == null ? "" :
                item.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return dto;
    }

    private KaiPinMaterial map(ResultSet rs) throws SQLException {
        KaiPinMaterial item = new KaiPinMaterial();
        item.setId(rs.getLong("id"));
        item.setTitle(rs.getString("title"));
        item.setPrompt(rs.getString("prompt"));
        item.setImagePath(rs.getString("image_path"));
        item.setOriginalName(rs.getString("original_name"));
        String createdAt = rs.getString("created_at");
        item.setCreatedAt(createdAt == null || createdAt.isBlank() ? LocalDateTime.now() : LocalDateTime.parse(createdAt));
        return item;
    }

    private String suffix(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private String cleanTitle(String title, String fallback) {
        String t = title == null ? "" : title.trim();
        if (t.isBlank()) t = fallback == null ? "开品素材" : fallback;
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }
}
