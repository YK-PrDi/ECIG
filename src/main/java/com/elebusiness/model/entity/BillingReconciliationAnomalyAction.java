package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_reconciliation_anomaly_action",
       uniqueConstraints = @UniqueConstraint(name = "uk_billing_reconciliation_anomaly_action_key", columnNames = "anomalyKey"),
       indexes = {
           @Index(name = "idx_billing_reconciliation_anomaly_action_key", columnList = "anomalyKey"),
           @Index(name = "idx_billing_reconciliation_anomaly_action_type_status_id", columnList = "type, status, id"),
           @Index(name = "idx_billing_reconciliation_anomaly_action_user_type_status_id", columnList = "userId, type, status, id"),
           @Index(name = "idx_billing_reconciliation_anomaly_action_updated_id", columnList = "updatedAt, id")
       })
public class BillingReconciliationAnomalyAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String anomalyKey;

    @Column(nullable = false, length = 64)
    private String type;

    @Column(nullable = false)
    private Long sourceId;

    private Long userId;

    @Column(length = 160)
    private String referenceNo;

    @Column(nullable = false, length = 32)
    private String status;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false)
    private Long operatorUserId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAnomalyKey() { return anomalyKey; }
    public void setAnomalyKey(String anomalyKey) { this.anomalyKey = anomalyKey; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(Long operatorUserId) { this.operatorUserId = operatorUserId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
