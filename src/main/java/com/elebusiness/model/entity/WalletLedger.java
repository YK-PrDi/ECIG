package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "wallet_ledger",
       uniqueConstraints = @UniqueConstraint(name = "uk_wallet_ledger_idempotency", columnNames = "idempotencyKey"),
       indexes = {
           @Index(name = "idx_wallet_ledger_user", columnList = "userId"),
           @Index(name = "idx_wallet_ledger_usage", columnList = "usageLogId"),
           @Index(name = "idx_wallet_ledger_created", columnList = "createdAt"),
           @Index(name = "idx_wallet_ledger_user_id", columnList = "userId, id"),
           @Index(name = "idx_wallet_ledger_user_created_id", columnList = "userId, createdAt, id"),
           @Index(name = "idx_wallet_ledger_user_type_direction_id", columnList = "userId, type, direction, id"),
           @Index(name = "idx_wallet_ledger_status_type_user", columnList = "status, type, userId"),
           @Index(name = "idx_wallet_ledger_type_direction_id", columnList = "type, direction, id"),
           @Index(name = "idx_wallet_ledger_type_status_id", columnList = "type, status, id")
       })
public class WalletLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    private Long usageLogId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 16)
    private String direction;

    @Column(nullable = false)
    private long pointsDelta;

    @Column(nullable = false)
    private long balanceBefore;

    @Column(nullable = false)
    private long balanceAfter;

    @Column(nullable = false)
    private long frozenBefore;

    @Column(nullable = false)
    private long frozenAfter;

    @Column(nullable = false, length = 32)
    private String status = "POSTED";

    @Column(length = 128)
    private String idempotencyKey;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String remark;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getUsageLogId() { return usageLogId; }
    public void setUsageLogId(Long usageLogId) { this.usageLogId = usageLogId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public long getPointsDelta() { return pointsDelta; }
    public void setPointsDelta(long pointsDelta) { this.pointsDelta = pointsDelta; }
    public long getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(long balanceBefore) { this.balanceBefore = balanceBefore; }
    public long getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(long balanceAfter) { this.balanceAfter = balanceAfter; }
    public long getFrozenBefore() { return frozenBefore; }
    public void setFrozenBefore(long frozenBefore) { this.frozenBefore = frozenBefore; }
    public long getFrozenAfter() { return frozenAfter; }
    public void setFrozenAfter(long frozenAfter) { this.frozenAfter = frozenAfter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
