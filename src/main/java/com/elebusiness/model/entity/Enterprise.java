package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 企业（租户）：平台中控建企业并指定负责人；
 * 企业内的用户、资产库、点数分配都按 enterpriseId 隔离。
 */
@Entity
@Table(name = "enterprise",
       indexes = {
           @Index(name = "idx_enterprise_owner", columnList = "ownerId"),
           @Index(name = "idx_enterprise_created", columnList = "createdAt")
       })
public class Enterprise {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** 企业负责人（app_user.id），可为空（先建企业后指定） */
    private Long ownerId;

    @Column(length = 64)
    private String ownerName;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
