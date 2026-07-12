package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "generation_usage_log",
       indexes = {
           @Index(name = "idx_usage_user", columnList = "userId"),
           @Index(name = "idx_usage_task", columnList = "taskId"),
           @Index(name = "idx_usage_status", columnList = "status"),
           @Index(name = "idx_usage_started", columnList = "startedAt"),
           @Index(name = "idx_usage_user_id", columnList = "userId, id"),
           @Index(name = "idx_usage_user_started_id", columnList = "userId, startedAt, id"),
           @Index(name = "idx_usage_user_status_provider_mode_id", columnList = "userId, status, provider, mode, id"),
           @Index(name = "idx_usage_status_user", columnList = "status, userId"),
           @Index(name = "idx_usage_status_provider_mode_id", columnList = "status, provider, mode, id"),
           @Index(name = "idx_usage_provider_task_id", columnList = "provider, providerTaskId, id"),
           @Index(name = "idx_usage_cost_source_id", columnList = "costSource, id")
       })
public class GenerationUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String taskId;

    @Column(nullable = false, length = 32)
    private String mode;

    @Column(nullable = false, length = 64)
    private String agentId;

    @Column(length = 64)
    private String provider;

    @Column(nullable = false)
    private int estimatedPoints = 0;

    @Column(nullable = false)
    private int actualPoints = 0;

    @Column(length = 128)
    private String providerTaskId;

    @Column(precision = 18, scale = 6)
    private BigDecimal providerRawCost;

    @Column(length = 32)
    private String providerRawUnit;

    @Column(length = 32)
    private String costSource;

    @Column(precision = 18, scale = 6)
    private BigDecimal exchangeRate;

    @Column(nullable = false, length = 32)
    private String status = "STARTED";

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) startedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public int getEstimatedPoints() { return estimatedPoints; }
    public void setEstimatedPoints(int estimatedPoints) { this.estimatedPoints = estimatedPoints; }
    public int getActualPoints() { return actualPoints; }
    public void setActualPoints(int actualPoints) { this.actualPoints = actualPoints; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public BigDecimal getProviderRawCost() { return providerRawCost; }
    public void setProviderRawCost(BigDecimal providerRawCost) { this.providerRawCost = providerRawCost; }
    public String getProviderRawUnit() { return providerRawUnit; }
    public void setProviderRawUnit(String providerRawUnit) { this.providerRawUnit = providerRawUnit; }
    public String getCostSource() { return costSource; }
    public void setCostSource(String costSource) { this.costSource = costSource; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(BigDecimal exchangeRate) { this.exchangeRate = exchangeRate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
