package com.elebusiness.controller;

import com.elebusiness.service.VideoGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);
    private static final String OUTPUT_DIR = "./生成结果/视频";

    private final VideoGenerationService videoGenerationService;

    public VideoController(VideoGenerationService videoGenerationService) {
        this.videoGenerationService = videoGenerationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        String prompt = body.getOrDefault("prompt", "").trim();
        String aspectRatio = body.getOrDefault("aspectRatio", "16:9");

        if (prompt.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "message", "提示词不能为空"));
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String outputPath = OUTPUT_DIR + "/video_" + timestamp + ".mp4";

        try {
            String savedPath = videoGenerationService.generateVideo(prompt, aspectRatio, outputPath);
            // 返回相对于 OUTPUT_DIR 的文件名，供前端请求 /api/video/file 时使用
            String filename = new File(savedPath).getName();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "filename", filename,
                    "message", "视频生成成功"
            ));
        } catch (Exception e) {
            log.error("视频生成失败: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "生成失败: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/file")
    public ResponseEntity<FileSystemResource> file(@RequestParam String filename) {
        // 防止路径遍历
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        File f = new File(OUTPUT_DIR + "/" + filename);
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(f));
    }
}
