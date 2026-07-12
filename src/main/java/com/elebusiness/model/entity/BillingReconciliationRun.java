package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_reconciliation_run",
       uniqueConstraints = @UniqueConstraint(name = "uk_billing_reconciliation_run_id", columnNames = "runId"),
       indexes = {
           @Index(name = "idx_billing_reconciliation_run_scope_id", columnList = "scopeUserId, id"),
           @Index(name = "idx_billing_reconciliation_run_status_id", columnList = "status, id"),
           @Index(name = "idx_billing_reconciliation_run_trigger_id", columnList = "triggerType, id"),
           @Index(name = "idx_billing_reconciliation_run_started_id", columnList = "startedAt, id")
       })
public class BillingReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String runId;

    @Column(nullable = false)
    private Long scopeUserId;

    private Long triggeredByUserId;

    @Column(nullable = false, length = 32)
    private String triggerType = "MANUAL";

    @Column(nullable = false, length = 16)
    private String status = "RUNNING";

    @Column(nullable = false)
    private boolean healthy = false;

    @Column(nullable = false)
    private long checkCount = 0;

    @Column(nullable = false)
    private long anomalyCount = 0;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(nullable = false)
    private long durationMillis = 0;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String reportJson;

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Long getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(Long scopeUserId) { this.scopeUserId = scopeUserId; }
    public Long getTriggeredByUserId() { return triggeredByUserId; }
    public void setTriggeredByUserId(Long triggeredByUserId) { this.triggeredByUserId = triggeredByUserId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public long getCheckCount() { return checkCount; }
    public void setCheckCount(long checkCount) { this.checkCount = checkCount; }
    public long getAnomalyCount() { return anomalyCount; }
    public void setAnomalyCount(long anomalyCount) { this.anomalyCount = anomalyCount; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
}
