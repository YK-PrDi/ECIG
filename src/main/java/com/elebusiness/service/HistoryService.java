package com.elebusiness.service;

import com.elebusiness.config.AppProperties;
import com.elebusiness.model.entity.GenerationHistory;
import com.elebusiness.repository.GenerationHistoryRepository;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2 集成层：把"参考图归档 + 写 GenerationHistory + 回填 savedPath" 3 个动作集中在这里。
 * Controller 调本服务而不是直接操作 repo，让 controller 保持 thin。
 *
 * 参考图归档策略（用户决策"完整版含参考图"）：
 * - 生图前调 archiveRefs(refs)，每张 ref 复制一份到 .history-refs/{generationUuid}/N.{ext}，返回相对路径列表
 * - DB 存这个相对路径列表的 JSON，UI 重生时按路径读出 ref 文件
 * - .history-refs/ 不参与 .temp-output 的 2h TTL 清理；只在用户手动删除历史条目时才清理
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    private final GenerationHistoryRepository generationRepo;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public HistoryService(GenerationHistoryRepository generationRepo, AppProperties appProperties) {
        this.generationRepo = generationRepo;
        this.appProperties = appProperties;
    }

    /**
     * 把上传的 MultipartFile 参考图归档到 .history-refs/{generationUuid}/N.{ext}。
     * 返回相对路径列表（相对项目根，前端可以用 / 拼出 GET /api/image?path= 来读）。
     * 失败时返回空列表，不抛异常 — 历史归档失败不应阻断生图主流程。
     */
    public ArchiveResult archiveRefs(List<MultipartFile> refs) {
        ArchiveResult result = new ArchiveResult();
        if (refs == null || refs.isEmpty()) return result;

        String genId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        File rootDir = new File(appProperties.getPaths().getHistoryRefsDir(), genId);
        rootDir.mkdirs();

        result.generationId = genId;
        for (int i = 0; i < refs.size(); i++) {
            MultipartFile mf = refs.get(i);
            if (mf == null || mf.isEmpty()) continue;
            String original = mf.getOriginalFilename();
            String ext = ".jpg";
            if (original != null) {
                int dot = original.lastIndexOf('.');
                if (dot > 0 && dot < original.length() - 1) ext = original.substring(dot);
            }
            File target = new File(rootDir, i + ext);
            try (InputStream is = mf.getInputStream()) {
                Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                result.refPaths.add(toRelative(target));
            } catch (IOException e) {
                log.warn("history-refs 归档失败 idx={}: {}", i, e.getMessage());
            }
        }
        return result;
    }

    /** 重新对 File（已经在磁盘上的临时文件，比如 customGenerate 内部已 transferTo 过的）做归档。 */
    public ArchiveResult archiveRefFiles(List<File> refs) {
        ArchiveResult result = new ArchiveResult();
        if (refs == null || refs.isEmpty()) return result;

        String genId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        File rootDir = new File(appProperties.getPaths().getHistoryRefsDir(), genId);
        rootDir.mkdirs();

        result.generationId = genId;
        for (int i = 0; i < refs.size(); i++) {
            File src = refs.get(i);
            if (src == null || !src.exists()) continue;
            String name = src.getName();
            String ext = ".jpg";
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) ext = name.substring(dot);
            File target = new File(rootDir, i + ext);
            try {
                Files.copy(src.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                result.refPaths.add(toRelative(target));
            } catch (IOException e) {
                log.warn("history-refs 归档失败 idx={}: {}", i, e.getMessage());
            }
        }
        return result;
    }

    /**
     * 写一条 GenerationHistory。outputPath 是 .temp-output 下的临时绝对路径，
     * 用户点 💾 后会通过 markSaved 回填 savedPath。
     */
    /** 解析 refPathsJson 为相对路径 list；不可读时返回空列表（不抛）。 */
    public List<String> parseRefPaths(String refPathsJson) {
        if (refPathsJson == null || refPathsJson.isBlank()) return java.util.Collections.emptyList();
        try {
            return objectMapper.readValue(refPathsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("refPathsJson 解析失败: {}", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** 把 ref 相对路径还原为项目根下绝对 File；用于前端取件 endpoint。 */
    public File resolveRefFile(String relPath) {
        if (relPath == null || relPath.isBlank()) return null;
        // 安全：必须落在 historyRefsDir 内，防路径穿越
        File root = new File(appProperties.getPaths().getHistoryRefsDir()).getAbsoluteFile();
        File rootCanonical;
        try { rootCanonical = root.getCanonicalFile(); } catch (Exception e) { rootCanonical = root; }
        File target = new File(System.getProperty("user.dir"), relPath).getAbsoluteFile();
        File targetCanonical;
        try { targetCanonical = target.getCanonicalFile(); } catch (Exception e) { targetCanonical = target; }
        if (!targetCanonical.getAbsolutePath().startsWith(rootCanonical.getAbsolutePath())) return null;
        if (!targetCanonical.exists() || !targetCanonical.isFile()) return null;
        return targetCanonical;
    }

    /** 删历史条目时同步清空 .history-refs/{genId}/ 目录；refPathsJson 反推目录。 */
    public void deleteArchivedRefs(String refPathsJson) {
        List<String> rels = parseRefPaths(refPathsJson);
        if (rels.isEmpty()) return;
        java.util.Set<File> dirs = new java.util.HashSet<>();
        for (String r : rels) {
            File f = new File(System.getProperty("user.dir"), r);
            File parent = f.getParentFile();
            if (parent != null) dirs.add(parent);
        }
        File root = new File(appProperties.getPaths().getHistoryRefsDir()).getAbsoluteFile();
        for (File dir : dirs) {
            // 安全：仅清 historyRefsDir 子目录
            try {
                if (!dir.getCanonicalPath().startsWith(root.getCanonicalPath())) continue;
            } catch (Exception e) { continue; }
            File[] kids = dir.listFiles();
            if (kids != null) for (File k : kids) k.delete();
            dir.delete();
        }
    }

    public Optional<GenerationHistory> recordGeneration(String sessionId, String mode, String prompt,
                                                       String agentId, List<String> refPaths,
                                                       String outputPath, String configJson) {
        try {
            GenerationHistory gh = new GenerationHistory();
            gh.setSessionId(sessionId);
            gh.setMode(mode);
            gh.setPrompt(prompt == null ? "" : prompt);
            gh.setAgentId(agentId == null ? "" : agentId);
            gh.setOutputPath(outputPath);
            gh.setConfigJson(configJson);
            if (refPaths != null && !refPaths.isEmpty()) {
                try {
                    gh.setRefPathsJson(objectMapper.writeValueAsString(refPaths));
                } catch (JsonProcessingException e) {
                    log.warn("refPaths 序列化失败: {}", e.getMessage());
                }
            }
            return Optional.of(generationRepo.save(gh));
        } catch (Exception e) {
            log.warn("recordGeneration 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** saveToGallery 成功时调用：把对应 outputPath 的历史条目标记为已保存。 */
    public void markSaved(String tempOutputPath, String savedPath) {
        try {
            generationRepo.findFirstByOutputPath(tempOutputPath).ifPresent(gh -> {
                gh.setSaved(true);
                gh.setSavedPath(savedPath);
                generationRepo.save(gh);
            });
        } catch (Exception e) {
            log.warn("markSaved 失败: {}", e.getMessage());
        }
    }

    /** 绝对路径转相对（相对项目根 user.dir），用 / 分隔。 */
    private String toRelative(File abs) {
        try {
            File root = new File(System.getProperty("user.dir")).getCanonicalFile();
            String absStr = abs.getCanonicalPath();
            if (absStr.startsWith(root.getCanonicalPath())) {
                String rel = absStr.substring(root.getCanonicalPath().length());
                return rel.replace('\\', '/').replaceFirst("^/", "");
            }
            return absStr;
        } catch (IOException e) {
            return abs.getAbsolutePath();
        }
    }

    public static class ArchiveResult {
        public String generationId = "";
        public List<String> refPaths = new ArrayList<>();
        public boolean isEmpty() { return refPaths.isEmpty(); }
    }
}
