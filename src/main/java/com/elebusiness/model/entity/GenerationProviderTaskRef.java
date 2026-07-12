package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "generation_provider_task_ref",
       uniqueConstraints = @UniqueConstraint(name = "uk_provider_task_ref_provider_task", columnNames = {"provider", "providerTaskId"}),
       indexes = {
           @Index(name = "idx_provider_task_ref_provider_task_id", columnList = "provider, providerTaskId, id"),
           @Index(name = "idx_provider_task_ref_usage_id", columnList = "usageLogId, id"),
           @Index(name = "idx_provider_task_ref_user_id", columnList = "userId, id")
       })
public class GenerationProviderTaskRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long usageLogId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 128)
    private String providerTaskId;

    @Column(length = 32)
    private String costSource;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUsageLogId() { return usageLogId; }
    public void setUsageLogId(Long usageLogId) { this.usageLogId = usageLogId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public String getCostSource() { return costSource; }
    public void setCostSource(String costSource) { this.costSource = costSource; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
