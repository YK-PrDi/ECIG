package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_daily_summary",
       uniqueConstraints = @UniqueConstraint(name = "uk_billing_daily_summary_scope_date", columnNames = {"scopeUserId", "summaryDate"}),
       indexes = {
           @Index(name = "idx_billing_daily_summary_scope_date", columnList = "scopeUserId, summaryDate"),
           @Index(name = "idx_billing_daily_summary_date_scope", columnList = "summaryDate, scopeUserId")
       })
public class BillingDailySummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scopeUserId;

    @Column(nullable = false)
    private LocalDate summaryDate;

    @Column(nullable = false)
    private long inPoints = 0;

    @Column(nullable = false)
    private long outPoints = 0;

    @Column(nullable = false)
    private long frozenPointsDelta = 0;

    @Column(nullable = false)
    private long releasedPoints = 0;

    @Column(nullable = false)
    private long ledgerCount = 0;

    @Column(nullable = false)
    private long usageCount = 0;

    @Column(nullable = false)
    private long succeededUsageCount = 0;

    @Column(nullable = false)
    private long failedUsageCount = 0;

    @Column(nullable = false)
    private long runningUsageCount = 0;

    @Column(nullable = false)
    private long actualPoints = 0;

    @Column(nullable = false)
    private long estimatedPoints = 0;

    @Column(nullable = false)
    private long orderCount = 0;

    @Column(nullable = false)
    private long paidOrderCount = 0;

    @Column(nullable = false)
    private long pendingOrderCount = 0;

    @Column(nullable = false)
    private long paidAmountCents = 0;

    @Column(nullable = false)
    private long pendingAmountCents = 0;

    @Column(nullable = false)
    private LocalDateTime refreshedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        if (refreshedAt == null) refreshedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        refreshedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(Long scopeUserId) { this.scopeUserId = scopeUserId; }
    public LocalDate getSummaryDate() { return summaryDate; }
    public void setSummaryDate(LocalDate summaryDate) { this.summaryDate = summaryDate; }
    public long getInPoints() { return inPoints; }
    public void setInPoints(long inPoints) { this.inPoints = inPoints; }
    public long getOutPoints() { return outPoints; }
    public void setOutPoints(long outPoints) { this.outPoints = outPoints; }
    public long getFrozenPointsDelta() { return frozenPointsDelta; }
    public void setFrozenPointsDelta(long frozenPointsDelta) { this.frozenPointsDelta = frozenPointsDelta; }
    public long getReleasedPoints() { return releasedPoints; }
    public void setReleasedPoints(long releasedPoints) { this.releasedPoints = releasedPoints; }
    public long getLedgerCount() { return ledgerCount; }
    public void setLedgerCount(long ledgerCount) { this.ledgerCount = ledgerCount; }
    public long getUsageCount() { return usageCount; }
    public void setUsageCount(long usageCount) { this.usageCount = usageCount; }
    public long getSucceededUsageCount() { return succeededUsageCount; }
    public void setSucceededUsageCount(long succeededUsageCount) { this.succeededUsageCount = succeededUsageCount; }
    public long getFailedUsageCount() { return failedUsageCount; }
    public void setFailedUsageCount(long failedUsageCount) { this.failedUsageCount = failedUsageCount; }
    public long getRunningUsageCount() { return runningUsageCount; }
    public void setRunningUsageCount(long runningUsageCount) { this.runningUsageCount = runningUsageCount; }
    public long getActualPoints() { return actualPoints; }
    public void setActualPoints(long actualPoints) { this.actualPoints = actualPoints; }
    public long getEstimatedPoints() { return estimatedPoints; }
    public void setEstimatedPoints(long estimatedPoints) { this.estimatedPoints = estimatedPoints; }
    public long getOrderCount() { return orderCount; }
    public void setOrderCount(long orderCount) { this.orderCount = orderCount; }
    public long getPaidOrderCount() { return paidOrderCount; }
    public void setPaidOrderCount(long paidOrderCount) { this.paidOrderCount = paidOrderCount; }
    public long getPendingOrderCount() { return pendingOrderCount; }
    public void setPendingOrderCount(long pendingOrderCount) { this.pendingOrderCount = pendingOrderCount; }
    public long getPaidAmountCents() { return paidAmountCents; }
    public void setPaidAmountCents(long paidAmountCents) { this.paidAmountCents = paidAmountCents; }
    public long getPendingAmountCents() { return pendingAmountCents; }
    public void setPendingAmountCents(long pendingAmountCents) { this.pendingAmountCents = pendingAmountCents; }
    public LocalDateTime getRefreshedAt() { return refreshedAt; }
    public void setRefreshedAt(LocalDateTime refreshedAt) { this.refreshedAt = refreshedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
