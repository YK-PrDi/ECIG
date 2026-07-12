package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.ConversationHistory;
import com.elebusiness.model.entity.GenerationHistory;
import com.elebusiness.repository.GenerationHistoryRepository;
import com.elebusiness.service.workspace.UserStorageService;
import com.elebusiness.service.workspace.UserWorkspaceDatabaseService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 历史服务。
 *
 * 新架构：生成历史、对话历史和参考图归档写入用户自己的 workspace.db / files 目录。
 * 旧无 userId 方法暂时委托到 admin 用户，保证旧调用点在迁移期间不崩。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);
    private static final long LEGACY_ADMIN_USER_ID = 1L;

    private final GenerationHistoryRepository legacyGenerationRepo;
    private final AppProperties appProperties;
    private final UserWorkspaceDatabaseService databaseService;
    private final UserStorageService storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoryService(GenerationHistoryRepository legacyGenerationRepo,
                          AppProperties appProperties,
                          UserWorkspaceDatabaseService databaseService,
                          UserStorageService storageService) {
        this.legacyGenerationRepo = legacyGenerationRepo;
        this.appProperties = appProperties;
        this.databaseService = databaseService;
        this.storageService = storageService;
    }

    public ArchiveResult archiveRefs(List<MultipartFile> refs) {
        return archiveRefs(LEGACY_ADMIN_USER_ID, refs);
    }

    public ArchiveResult archiveRefs(long userId, List<MultipartFile> refs) {
        ArchiveResult result = new ArchiveResult();
        if (refs == null || refs.isEmpty()) return result;

        Path rootDir = newArchiveDir(userId);
        for (int i = 0; i < refs.size(); i++) {
            MultipartFile mf = refs.get(i);
            if (mf == null || mf.isEmpty()) continue;
            Path target = rootDir.resolve(i + suffix(mf.getOriginalFilename())).normalize();
            try (InputStream is = mf.getInputStream()) {
                Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                result.refPaths.add(toUserRelative(userId, target));
            } catch (IOException e) {
                log.warn("history-refs 归档失败 idx={}: {}", i, e.getMessage());
            }
        }
        result.generationId = rootDir.getFileName().toString();
        return result;
    }

    public ArchiveResult archiveRefFiles(List<File> refs) {
        return archiveRefFiles(LEGACY_ADMIN_USER_ID, refs);
    }

    public ArchiveResult archiveRefFiles(long userId, List<File> refs) {
        ArchiveResult result = new ArchiveResult();
        if (refs == null || refs.isEmpty()) return result;

        Path rootDir = newArchiveDir(userId);
        for (int i = 0; i < refs.size(); i++) {
            File src = refs.get(i);
            if (src == null || !src.exists()) continue;
            Path target = rootDir.resolve(i + suffix(src.getName())).normalize();
            try {
                Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                result.refPaths.add(toUserRelative(userId, target));
            } catch (IOException e) {
                log.warn("history-refs 归档失败 idx={}: {}", i, e.getMessage());
            }
        }
        result.generationId = rootDir.getFileName().toString();
        return result;
    }

    public List<String> parseRefPaths(String refPathsJson) {
        if (refPathsJson == null || refPathsJson.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(refPathsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("refPathsJson 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public File resolveRefFile(String relPath) {
        return resolveRefFile(LEGACY_ADMIN_USER_ID, relPath);
    }

    public File resolveRefFile(long userId, String relPath) {
        if (relPath == null || relPath.isBlank()) return null;
        Path filesRoot = storageService.filesRoot(userId).toAbsolutePath().normalize();
        Path target = filesRoot.resolve(relPath).toAbsolutePath().normalize();
        if (!target.startsWith(filesRoot)) return null;
        if (!Files.isRegularFile(target)) return null;
        return target.toFile();
    }

    public void deleteArchivedRefs(String refPathsJson) {
        deleteArchivedRefs(LEGACY_ADMIN_USER_ID, refPathsJson);
    }

    public void deleteArchivedRefs(long userId, String refPathsJson) {
        Set<Path> dirs = new HashSet<>();
        for (String rel : parseRefPaths(refPathsJson)) {
            File file = resolveRefFile(userId, rel);
            if (file != null && file.getParentFile() != null) {
                dirs.add(file.getParentFile().toPath());
            }
        }
        Path root = storageService.historyRefsRoot(userId).toAbsolutePath().normalize();
        for (Path dir : dirs) {
            Path normalized = dir.toAbsolutePath().normalize();
            if (!normalized.startsWith(root)) continue;
            deleteRecursively(normalized.toFile());
        }
    }

    public Optional<GenerationHistory> recordGeneration(String sessionId, String mode, String prompt,
                                                       String agentId, List<String> refPaths,
                                                       String outputPath, String configJson) {
        return recordGeneration(LEGACY_ADMIN_USER_ID, sessionId, mode, prompt, agentId, refPaths, outputPath, configJson);
    }

    public Optional<GenerationHistory> recordGeneration(long userId, String sessionId, String mode, String prompt,
                                                       String agentId, List<String> refPaths,
                                                       String outputPath, String configJson) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     insert into generation_history(
                       session_id, mode, prompt, config_json, agent_id, ref_paths_json,
                       output_path, saved_path, saved, created_at
                     ) values (?, ?, ?, ?, ?, ?, ?, null, 0, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            String refJson = "";
            if (refPaths != null && !refPaths.isEmpty()) {
                try {
                    refJson = objectMapper.writeValueAsString(refPaths);
                } catch (JsonProcessingException e) {
                    log.warn("refPaths 序列化失败: {}", e.getMessage());
                }
            }
            String now = LocalDateTime.now().toString();
            ps.setString(1, blankTo(sessionId, "default"));
            ps.setString(2, blankTo(mode, "unknown"));
            ps.setString(3, prompt == null ? "" : prompt);
            ps.setString(4, configJson);
            ps.setString(5, blankTo(agentId, ""));
            ps.setString(6, refJson);
            ps.setString(7, outputPath);
            ps.setString(8, now);
            ps.executeUpdate();

            GenerationHistory gh = new GenerationHistory();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) gh.setId(keys.getLong(1));
            }
            gh.setSessionId(blankTo(sessionId, "default"));
            gh.setMode(blankTo(mode, "unknown"));
            gh.setPrompt(prompt == null ? "" : prompt);
            gh.setConfigJson(configJson);
            gh.setAgentId(blankTo(agentId, ""));
            gh.setRefPathsJson(refJson);
            gh.setOutputPath(outputPath);
            gh.setSaved(false);
            gh.setCreatedAt(LocalDateTime.parse(now));
            return Optional.of(gh);
        } catch (Exception e) {
            log.warn("recordGeneration 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public PageResult<GenerationHistory> listGenerations(long userId, String sessionId, String mode, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(200, size));
        String where = "";
        List<Object> args = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            where = " where session_id = ?";
            args.add(sessionId);
        } else if (mode != null && !mode.isBlank()) {
            where = " where mode = ?";
            args.add(mode);
        }

        long total = count(userId, "generation_history", where, args);
        List<GenerationHistory> items = new ArrayList<>();
        String sql = """
                select id, session_id, mode, prompt, config_json, agent_id, ref_paths_json,
                       output_path, saved_path, saved, created_at
                from generation_history
                """ + where + " order by created_at desc limit ? offset ?";
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindArgs(ps, args);
            ps.setInt(args.size() + 1, safeSize);
            ps.setInt(args.size() + 2, safePage * safeSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(mapGeneration(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取生成历史失败", e);
        }
        return new PageResult<>(items, safePage, safeSize, total, totalPages(total, safeSize));
    }

    public Optional<GenerationHistory> findGeneration(long userId, long id) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     select id, session_id, mode, prompt, config_json, agent_id, ref_paths_json,
                            output_path, saved_path, saved, created_at
                     from generation_history where id = ?
                     """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapGeneration(rs));
            }
            return Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("读取生成历史详情失败", e);
        }
    }

    public boolean deleteGeneration(long userId, long id) {
        Optional<GenerationHistory> opt = findGeneration(userId, id);
        if (opt.isEmpty()) return false;
        deleteArchivedRefs(userId, opt.get().getRefPathsJson());
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("delete from generation_history where id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除生成历史失败", e);
        }
    }

    public void markSaved(String tempOutputPath, String savedPath) {
        markSaved(LEGACY_ADMIN_USER_ID, tempOutputPath, savedPath);
        if (legacyGenerationRepo != null) {
            try {
                legacyGenerationRepo.findFirstByOutputPath(tempOutputPath).ifPresent(gh -> {
                    gh.setSaved(true);
                    gh.setSavedPath(savedPath);
                    legacyGenerationRepo.save(gh);
                });
            } catch (Exception e) {
                log.warn("legacy markSaved 失败: {}", e.getMessage());
            }
        }
    }

    public void markSaved(long userId, String tempOutputPath, String savedPath) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     update generation_history
                     set saved = 1, saved_path = ?
                     where output_path = ?
                     """)) {
            ps.setString(1, savedPath);
            ps.setString(2, tempOutputPath);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("markSaved 失败: {}", e.getMessage());
        }
    }

    public PageResult<ConversationHistory> listConversations(long userId, String sessionId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(200, size));
        String where = "";
        List<Object> args = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            where = " where session_id = ?";
            args.add(sessionId);
        }
        long total = count(userId, "conversation_history", where, args);
        List<ConversationHistory> items = new ArrayList<>();
        String sql = """
                select id, session_id, role, content, mode, created_at
                from conversation_history
                """ + where + " order by created_at desc limit ? offset ?";
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            bindArgs(ps, args);
            ps.setInt(args.size() + 1, safeSize);
            ps.setInt(args.size() + 2, safePage * safeSize);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) items.add(mapConversation(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取对话历史失败", e);
        }
        return new PageResult<>(items, safePage, safeSize, total, totalPages(total, safeSize));
    }

    public ConversationHistory writeConversation(long userId, String sessionId, String role, String content, String mode) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("""
                     insert into conversation_history(session_id, role, content, mode, created_at)
                     values (?, ?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            String now = LocalDateTime.now().toString();
            ps.setString(1, blankTo(sessionId, "default"));
            ps.setString(2, blankTo(role, "user"));
            ps.setString(3, content == null ? "" : content);
            ps.setString(4, mode);
            ps.setString(5, now);
            ps.executeUpdate();
            ConversationHistory c = new ConversationHistory();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) c.setId(keys.getLong(1));
            }
            c.setSessionId(blankTo(sessionId, "default"));
            c.setRole(blankTo(role, "user"));
            c.setContent(content == null ? "" : content);
            c.setMode(mode);
            c.setCreatedAt(LocalDateTime.parse(now));
            return c;
        } catch (SQLException e) {
            throw new IllegalStateException("写入对话历史失败", e);
        }
    }

    public boolean deleteConversation(long userId, long id) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("delete from conversation_history where id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("删除对话历史失败", e);
        }
    }

    public long clearConversations(long userId, String sessionId) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("delete from conversation_history where session_id = ?")) {
            ps.setString(1, sessionId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清空对话历史失败", e);
        }
    }

    private Path newArchiveDir(long userId) {
        String genId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path dir = storageService.historyRefsRoot(userId).resolve(genId).normalize();
        storageService.ensureDirectory(dir);
        return dir;
    }

    private String toUserRelative(long userId, Path path) {
        Path root = storageService.filesRoot(userId).toAbsolutePath().normalize();
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private long count(long userId, String table, String where, List<Object> args) {
        try (Connection conn = databaseService.openConnection(userId);
             PreparedStatement ps = conn.prepareStatement("select count(*) from " + table + where)) {
            bindArgs(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计历史数量失败", e);
        }
    }

    private void bindArgs(PreparedStatement ps, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    private GenerationHistory mapGeneration(ResultSet rs) throws SQLException {
        GenerationHistory g = new GenerationHistory();
        g.setId(rs.getLong("id"));
        g.setSessionId(rs.getString("session_id"));
        g.setMode(rs.getString("mode"));
        g.setPrompt(rs.getString("prompt"));
        g.setConfigJson(rs.getString("config_json"));
        g.setAgentId(rs.getString("agent_id"));
        g.setRefPathsJson(rs.getString("ref_paths_json"));
        g.setOutputPath(rs.getString("output_path"));
        g.setSavedPath(rs.getString("saved_path"));
        g.setSaved(rs.getInt("saved") == 1);
        g.setCreatedAt(parseDate(rs.getString("created_at")));
        return g;
    }

    private ConversationHistory mapConversation(ResultSet rs) throws SQLException {
        ConversationHistory c = new ConversationHistory();
        c.setId(rs.getLong("id"));
        c.setSessionId(rs.getString("session_id"));
        c.setRole(rs.getString("role"));
        c.setContent(rs.getString("content"));
        c.setMode(rs.getString("mode"));
        c.setCreatedAt(parseDate(rs.getString("created_at")));
        return c;
    }

    private LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        return LocalDateTime.parse(value);
    }

    private int totalPages(long total, int size) {
        return (int) Math.ceil(total * 1.0 / size);
    }

    private String suffix(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".jpeg")) return ".jpeg";
        return ".jpg";
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return false;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete();
    }

    public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }

    public static class ArchiveResult {
        public String generationId = "";
        public List<String> refPaths = new ArrayList<>();
        public boolean isEmpty() { return refPaths.isEmpty(); }
    }
}
