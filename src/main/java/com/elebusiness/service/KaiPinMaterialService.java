package com.elebusiness.service;

import com.elebusiness.model.entity.KaiPinMaterial;
import com.elebusiness.repository.KaiPinMaterialRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class KaiPinMaterialService {

    private final KaiPinMaterialRepository repository;

    public KaiPinMaterialService(KaiPinMaterialRepository repository) {
        this.repository = repository;
    }

    public KaiPinMaterial save(MultipartFile image, String title, String prompt) throws Exception {
        if (image == null || image.isEmpty()) throw new IllegalArgumentException("请先上传素材图片");
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("请先生成或填写素材提示词");

        File dir = new File(System.getProperty("user.dir"), ".kaipin-materials");
        dir.mkdirs();

        String original = Optional.ofNullable(image.getOriginalFilename()).orElse("material.jpg");
        String ext = suffix(original);
        String name = UUID.randomUUID().toString().replace("-", "").substring(0, 16) + ext;
        File target = new File(dir, name).getCanonicalFile();

        try (InputStream is = image.getInputStream()) {
            Files.copy(is, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        KaiPinMaterial item = new KaiPinMaterial();
        item.setTitle(cleanTitle(title, original));
        item.setPrompt(prompt.trim());
        item.setImagePath(target.getAbsolutePath());
        item.setOriginalName(original);
        return repository.save(item);
    }

    public List<KaiPinMaterial> list(int limit) {
        int safeLimit = Math.max(1, Math.min(200, limit));
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, safeLimit)).getContent();
    }

    public List<KaiPinMaterial> findAllByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return repository.findByIdIn(ids);
    }

    public void delete(Long id) {
        repository.findById(id).ifPresent(item -> {
            repository.delete(item);
            try {
                File img = new File(item.getImagePath()).getCanonicalFile();
                File root = new File(System.getProperty("user.dir"), ".kaipin-materials").getCanonicalFile();
                if (img.getAbsolutePath().startsWith(root.getAbsolutePath()) && img.exists()) {
                    img.delete();
                }
            } catch (Exception ignored) {
            }
        });
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
