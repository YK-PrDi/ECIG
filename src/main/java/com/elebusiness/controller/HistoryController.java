package com.elebusiness.controller;

import com.elebusiness.model.entity.ConversationHistory;
import com.elebusiness.model.entity.GenerationHistory;
import com.elebusiness.service.HistoryService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private final HistoryService historyService;
    private final CurrentUserService currentUserService;

    public HistoryController(HistoryService historyService, CurrentUserService currentUserService) {
        this.historyService = historyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/conversations")
    public Map<String, Object> listConversations(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        HistoryService.PageResult<ConversationHistory> result =
                historyService.listConversations(userId, sessionId, page, size);
        return pagedResponse(result);
    }

    @PostMapping("/conversations")
    public Map<String, Object> writeConversation(@RequestBody Map<String, String> body,
                                                 HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        ConversationHistory saved = historyService.writeConversation(
                userId,
                body == null ? "default" : body.getOrDefault("sessionId", "default"),
                body == null ? "user" : body.getOrDefault("role", "user"),
                body == null ? "" : body.getOrDefault("content", ""),
                body == null ? null : body.get("mode")
        );
        return Map.of("success", true, "id", saved.getId());
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@PathVariable Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        boolean success = historyService.deleteConversation(userId, id);
        return success ? Map.of("success", true) : Map.of("success", false, "error", "条目不存在");
    }

    @DeleteMapping("/conversations")
    public Map<String, Object> clearConversations(@RequestParam String sessionId, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        long count = historyService.clearConversations(userId, sessionId);
        return Map.of("success", true, "deleted", count);
    }

    @GetMapping("/generations")
    public Map<String, Object> listGenerations(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        HistoryService.PageResult<GenerationHistory> result =
                historyService.listGenerations(userId, sessionId, mode, page, size);
        return pagedResponse(result);
    }

    @GetMapping("/generations/{id}")
    public ResponseEntity<GenerationHistory> getGeneration(@PathVariable Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        return historyService.findGeneration(userId, id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/generations/{id}")
    public Map<String, Object> deleteGeneration(@PathVariable Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        boolean success = historyService.deleteGeneration(userId, id);
        return success ? Map.of("success", true) : Map.of("success", false, "error", "条目不存在");
    }

    @GetMapping("/refs/{id}")
    public ResponseEntity<Map<String, Object>> listRefs(@PathVariable Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        Optional<GenerationHistory> opt = historyService.findGeneration(userId, id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        List<String> rels = historyService.parseRefPaths(opt.get().getRefPathsJson());
        List<Map<String, String>> items = new java.util.ArrayList<>();
        for (String rel : rels) {
            File f = historyService.resolveRefFile(userId, rel);
            if (f == null) continue;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", f.getName());
            m.put("relPath", rel);
            m.put("url", "/api/history/ref?relPath=" + java.net.URLEncoder.encode(rel, java.nio.charset.StandardCharsets.UTF_8));
            items.add(m);
        }
        return ResponseEntity.ok(Map.of("id", id, "items", items));
    }

    @GetMapping("/ref")
    public ResponseEntity<FileSystemResource> getRef(@RequestParam String relPath, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        File f = historyService.resolveRefFile(userId, relPath);
        if (f == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(imageMime(f.getName())))
                .body(new FileSystemResource(f));
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<FileSystemResource> getThumbnail(@RequestParam Long id, HttpSession httpSession) {
        long userId = currentUserService.requireUserId(httpSession);
        Optional<GenerationHistory> opt = historyService.findGeneration(userId, id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        GenerationHistory g = opt.get();
        String outputPath = g.getOutputPath();
        if (outputPath == null || outputPath.isBlank()) return ResponseEntity.notFound().build();

        File f = new File(outputPath);
        if (!f.exists()) return ResponseEntity.notFound().build();

        File pick = null;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                java.util.Arrays.sort(kids);
                for (File k : kids) {
                    if (!k.isFile()) continue;
                    String n = k.getName().toLowerCase();
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                        pick = k;
                        break;
                    }
                }
            }
        } else {
            pick = f;
        }
        if (pick == null) return ResponseEntity.notFound().build();
        String mime = pick.getName().toLowerCase().endsWith(".mp4") ? "video/mp4" : imageMime(pick.getName());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime))
                .body(new FileSystemResource(pick));
    }

    private Map<String, Object> pagedResponse(HistoryService.PageResult<?> page) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", page.items());
        resp.put("page", page.page());
        resp.put("size", page.size());
        resp.put("totalElements", page.totalElements());
        resp.put("totalPages", page.totalPages());
        return resp;
    }

    private String imageMime(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
