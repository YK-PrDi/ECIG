package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_export_audit_log",
       uniqueConstraints = @UniqueConstraint(name = "uk_billing_export_audit_log_export_id", columnNames = "exportId"),
       indexes = {
           @Index(name = "idx_billing_export_audit_log_export_id", columnList = "exportId"),
           @Index(name = "idx_billing_export_audit_log_operator_id", columnList = "operatorUserId, id"),
           @Index(name = "idx_billing_export_audit_log_type_status_id", columnList = "exportType, status, id"),
           @Index(name = "idx_billing_export_audit_log_started_id", columnList = "startedAt, id")
       })
public class BillingExportAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String exportId;

    @Column(nullable = false)
    private Long operatorUserId;

    private Long scopeUserId;

    @Column(nullable = false, length = 64)
    private String exportType;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String filtersJson;

    @Column(nullable = false)
    private long rowCount = 0;

    @Column(nullable = false)
    private boolean truncated = false;

    @Column(nullable = false, length = 16)
    private String status = "SUCCESS";

    @Lob
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @Column(nullable = false)
    private long durationMillis = 0;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (startedAt == null) startedAt = now;
        if (finishedAt == null) finishedAt = startedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getExportId() { return exportId; }
    public void setExportId(String exportId) { this.exportId = exportId; }
    public Long getOperatorUserId() { return operatorUserId; }
    public void setOperatorUserId(Long operatorUserId) { this.operatorUserId = operatorUserId; }
    public Long getScopeUserId() { return scopeUserId; }
    public void setScopeUserId(Long scopeUserId) { this.scopeUserId = scopeUserId; }
    public String getExportType() { return exportType; }
    public void setExportType(String exportType) { this.exportType = exportType; }
    public String getFiltersJson() { return filtersJson; }
    public void setFiltersJson(String filtersJson) { this.filtersJson = filtersJson; }
    public long getRowCount() { return rowCount; }
    public void setRowCount(long rowCount) { this.rowCount = rowCount; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public long getDurationMillis() { return durationMillis; }
    public void setDurationMillis(long durationMillis) { this.durationMillis = durationMillis; }
}
