package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 企业资产库：全员共享的生成资产（图片/视频）。
 * 文件本体落在 userDataDir/company-assets/，本表只存元数据。
 */
@Entity
@Table(name = "company_asset",
       indexes = {
           @Index(name = "idx_company_asset_type", columnList = "type"),
           @Index(name = "idx_company_asset_uploader", columnList = "uploaderId"),
           @Index(name = "idx_company_asset_created", columnList = "createdAt")
       })
public class CompanyAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long uploaderId;

    /** 所属企业：资产库按企业隔离，SUPERADMIN 不可见 */
    private Long enterpriseId;

    @Column(nullable = false, length = 64)
    private String uploaderName;

    /** image / video */
    @Column(nullable = false, length = 16)
    private String type = "image";

    @Column(length = 200)
    private String title;

    /** 来源模式：standard / custom / ecommerce / kaipin / inpaint / video */
    @Column(length = 32)
    private String sourceMode;

    /** 企业库内的绝对路径（图片/视频文件） */
    @Column(nullable = false, length = 1024)
    private String storagePath;

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
    public Long getUploaderId() { return uploaderId; }
    public void setUploaderId(Long uploaderId) { this.uploaderId = uploaderId; }
    public Long getEnterpriseId() { return enterpriseId; }
    public void setEnterpriseId(Long enterpriseId) { this.enterpriseId = enterpriseId; }
    public String getUploaderName() { return uploaderName; }
    public void setUploaderName(String uploaderName) { this.uploaderName = uploaderName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSourceMode() { return sourceMode; }
    public void setSourceMode(String sourceMode) { this.sourceMode = sourceMode; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
