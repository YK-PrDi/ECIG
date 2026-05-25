package com.elebusiness.controller;

import com.elebusiness.model.entity.ConversationHistory;
import com.elebusiness.model.entity.GenerationHistory;
import com.elebusiness.repository.ConversationHistoryRepository;
import com.elebusiness.repository.GenerationHistoryRepository;
import com.elebusiness.service.HistoryService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 2：历史记录 API（生图 + 对话）。
 * 写入由后端在 finalizeProgressCard / saveToGallery / appendMessage 时主动调，
 * 前端只读 GET + DELETE。
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final ConversationHistoryRepository conversationRepo;
    private final GenerationHistoryRepository generationRepo;
    private final HistoryService historyService;

    public HistoryController(ConversationHistoryRepository conversationRepo,
                             GenerationHistoryRepository generationRepo,
                             HistoryService historyService) {
        this.conversationRepo = conversationRepo;
        this.generationRepo = generationRepo;
        this.historyService = historyService;
    }

    // ── 对话历史 ──────────────────────────────────────────────────

    @GetMapping("/conversations")
    public Map<String, Object> listConversations(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(200, size));
        Page<ConversationHistory> result = (sessionId == null || sessionId.isBlank())
                ? conversationRepo.findAllByOrderByCreatedAtDesc(pageable)
                : conversationRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
        return pagedResponse(result.getContent(), result);
    }

    /** 前端 appendMessage 时调用：写一条对话历史。 */
    @PostMapping("/conversations")
    public Map<String, Object> writeConversation(@RequestBody Map<String, String> body) {
        ConversationHistory c = new ConversationHistory();
        c.setSessionId(body.getOrDefault("sessionId", "default"));
        c.setRole(body.getOrDefault("role", "user"));
        c.setContent(body.getOrDefault("content", ""));
        c.setMode(body.get("mode"));
        ConversationHistory saved = conversationRepo.save(c);
        return Map.of("success", true, "id", saved.getId());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable Long id) {
        if (!conversationRepo.existsById(id)) {
            return Map.of("success", false, "error", "条目不存在");
        }
        conversationRepo.deleteById(id);
        return Map.of("success", true);
    }

    /** 清空某 session 全部对话（用户点"清空当前会话历史"） */
    @DeleteMapping("/conversations")
    @Transactional
    public Map<String, Object> clearConversations(@RequestParam String sessionId) {
        long count = conversationRepo.deleteBySessionId(sessionId);
        return Map.of("success", true, "deleted", count);
    }

    // ── 生图历史 ──────────────────────────────────────────────────

    @GetMapping("/generations")
    public Map<String, Object> listGenerations(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, size));
        Page<GenerationHistory> result;
        if (sessionId != null && !sessionId.isBlank()) {
            result = generationRepo.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable);
        } else if (mode != null && !mode.isBlank()) {
            result = generationRepo.findByModeOrderByCreatedAtDesc(mode, pageable);
        } else {
            result = generationRepo.findAllByOrderByCreatedAtDesc(pageable);
        }
        return pagedResponse(result.getContent(), result);
    }

    @GetMapping("/generations/{id}")
    public ResponseEntity<GenerationHistory> getGeneration(@PathVariable Long id) {
        return generationRepo.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/generations/{id}")
    public Map<String, Object> deleteGeneration(@PathVariable Long id) {
        Optional<GenerationHistory> opt = generationRepo.findById(id);
        if (opt.isEmpty()) {
            return Map.of("success", false, "error", "条目不存在");
        }
        // 同步清 .history-refs/{genId}/ 目录
        try { historyService.deleteArchivedRefs(opt.get().getRefPathsJson()); } catch (Exception ignored) {}
        generationRepo.deleteById(id);
        return Map.of("success", true);
    }

    /** 列出某条历史的归档参考图 URL（前端用于 🔄 重生时回显缩略图）。 */
    @GetMapping("/refs/{id}")
    public ResponseEntity<Map<String, Object>> listRefs(@PathVariable Long id) {
        Optional<GenerationHistory> opt = generationRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        List<String> rels = historyService.parseRefPaths(opt.get().getRefPathsJson());
        List<Map<String, String>> items = new java.util.ArrayList<>();
        for (String rel : rels) {
            File f = historyService.resolveRefFile(rel);
            if (f == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", f.getName());
            m.put("relPath", rel);
            m.put("url", "/api/history/ref?relPath=" + java.net.URLEncoder.encode(rel, java.nio.charset.StandardCharsets.UTF_8));
            items.add(m);
        }
        return ResponseEntity.ok(Map.of("id", id, "items", items));
    }

    /** 流式读取一张归档参考图。relPath 必须落在 historyRefsDir 内。 */
    @GetMapping("/ref")
    public ResponseEntity<FileSystemResource> getRef(@RequestParam String relPath) {
        File f = historyService.resolveRefFile(relPath);
        if (f == null) return ResponseEntity.notFound().build();
        String ln = f.getName().toLowerCase();
        String mime = ln.endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .body(new FileSystemResource(f));
    }

    /** 缩略图：图片模式取 outputPath 目录下第一张图；视频模式直接返回视频文件（前端用 <video> 显示首帧） */
    @GetMapping("/thumbnail")
    public ResponseEntity<FileSystemResource> getThumbnail(@RequestParam Long id) {
        Optional<GenerationHistory> opt = generationRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        GenerationHistory g = opt.get();
        String outputPath = g.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) return ResponseEntity.notFound().build();

        File f = new File(outputPath);
        if (!f.exists()) return ResponseEntity.notFound().build();

        File pick = null;
        if (f.isDirectory()) {
            // 找首张 jpg/png 当缩略图
            File[] kids = f.listFiles();
            if (kids != null) {
                java.util.Arrays.sort(kids);
                for (File k : kids) {
                    if (!k.isFile()) continue;
                    String n = k.getName().toLowerCase();
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) { pick = k; break; }
                }
            }
        } else {
            pick = f;
        }
        if (pick == null) return ResponseEntity.notFound().build();
        String ln = pick.getName().toLowerCase();
        String mime = ln.endsWith(".png") ? "image/png"
                : (ln.endsWith(".mp4") || ln.endsWith(".webm")) ? "video/mp4"
                : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .body(new FileSystemResource(pick));
    }

    // ── 通用工具 ──────────────────────────────────────────────────

    private Map<String, Object> pagedResponse(List<?> items, Page<?> page) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("page", page.getNumber());
        resp.put("size", page.getSize());
        resp.put("totalElements", page.getTotalElements());
        resp.put("totalPages", page.getTotalPages());
        return resp;
    }
}
