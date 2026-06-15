package com.elebusiness.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 开品模式素材库条目。
 * 图片落盘保存，数据库只记录图片路径和由 AI 生成/用户编辑后的创意提示词。
 */
@Entity
@Table(name = "kaipin_material",
       indexes = {
           @Index(name = "idx_kp_material_created", columnList = "createdAt")
       })
public class KaiPinMaterial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 120)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(nullable = false, length = 1024)
    private String imagePath;

    @Column(length = 255)
    private String originalName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
