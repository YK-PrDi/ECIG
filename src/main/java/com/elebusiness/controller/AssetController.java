package com.elebusiness.controller;

import com.elebusiness.model.entity.CompanyAsset;
import com.elebusiness.service.AssetService;
import com.elebusiness.service.auth.AuthService;
import com.elebusiness.service.auth.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业资产库：全公司共享的生成图片/视频。
 * GET    /api/assets              列表（分页，type 过滤）
 * POST   /api/assets              入库 {sourcePath, title?, type?, sourceMode?}
 * DELETE /api/assets/{id}         删除（上传者或管理员）
 * GET    /api/assets/{id}/file    预览流
 * GET    /api/assets/{id}/download 下载到电脑
 */
@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    private final AssetService assetService;
    private final CurrentUserService currentUserService;

    public AssetController(AssetService assetService, CurrentUserService currentUserService) {
        this.assetService = assetService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String type,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "60") int size,
                                    HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        Page<CompanyAsset> result = assetService.list(user.enterpriseId(), type, page, size);
        List<Map<String, Object>> items = result.getContent().stream().map(this::toMap).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("items", items);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return body;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> publish(@RequestBody Map<String, String> body,
                                                       HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        try {
            CompanyAsset asset;
            String videoFilename = body == null ? null : body.get("videoFilename");
            if (videoFilename != null && !videoFilename.isBlank()) {
                // 视频入库：按文件名从产出目录解析
                asset = assetService.publishVideo(
                        user, videoFilename,
                        body.get("title"),
                        body.get("sourceMode") == null ? "video" : body.get("sourceMode"));
            } else {
                asset = assetService.publish(
                        user,
                        body == null ? null : body.get("sourcePath"),
                        body == null ? null : body.get("title"),
                        body == null ? null : body.get("type"),
                        body == null ? null : body.get("sourceMode"));
            }
            return ResponseEntity.ok(Map.of("success", true, "item", toMap(asset)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", e.getMessage()));
        } catch (Exception e) {
            log.error("企业资产入库失败", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", "入库失败：" + e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        try {
            assetService.delete(id, user);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<FileSystemResource> file(@PathVariable long id, HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        Path path = assetService.fileOf(id, user.enterpriseId());
        if (path == null) return ResponseEntity.notFound().build();
        String lower = path.toString().toLowerCase();
        MediaType mime = lower.endsWith(".mp4") || lower.endsWith(".webm") ? MediaType.parseMediaType("video/mp4")
                : lower.endsWith(".png") ? MediaType.IMAGE_PNG
                : lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                : lower.endsWith(".gif") ? MediaType.IMAGE_GIF
                : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(mime).body(new FileSystemResource(path));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable long id, HttpSession session) {
        AuthService.AuthUser user = currentUserService.require(session);
        Path path = assetService.fileOf(id, user.enterpriseId());
        if (path == null) return ResponseEntity.notFound().build();
        String name = assetService.originalNameOf(id);
        if (name == null || name.isBlank() || name.contains("..") || name.contains("/") || name.contains("\\")) {
            name = path.getFileName().toString();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(new FileSystemResource(path));
    }

    private Map<String, Object> toMap(CompanyAsset asset) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", asset.getId());
        map.put("type", asset.getType());
        map.put("title", asset.getTitle());
        map.put("sourceMode", asset.getSourceMode());
        map.put("uploaderId", asset.getUploaderId());
        map.put("uploaderName", asset.getUploaderName());
        map.put("createdAt", asset.getCreatedAt() == null ? null : asset.getCreatedAt().toString());
        map.put("url", "/api/assets/" + asset.getId() + "/file");
        map.put("downloadUrl", "/api/assets/" + asset.getId() + "/download");
        return map;
    }
}
