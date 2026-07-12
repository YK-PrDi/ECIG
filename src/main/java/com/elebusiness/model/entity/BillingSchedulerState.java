package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_scheduler_state",
       uniqueConstraints = @UniqueConstraint(name = "uk_billing_scheduler_state_key", columnNames = "stateKey"),
       indexes = {
           @Index(name = "idx_billing_scheduler_state_key", columnList = "stateKey"),
           @Index(name = "idx_billing_scheduler_state_lease_until", columnList = "leaseUntil"),
           @Index(name = "idx_billing_scheduler_state_updated", columnList = "updatedAt")
       })
public class BillingSchedulerState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 96)
    private String stateKey;

    private Long lastUserId;

    @Column(length = 96)
    private String leaseOwner;

    private LocalDateTime leaseUntil;

    @Version
    private Long version;

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
    public String getStateKey() { return stateKey; }
    public void setStateKey(String stateKey) { this.stateKey = stateKey; }
    public Long getLastUserId() { return lastUserId; }
    public void setLastUserId(Long lastUserId) { this.lastUserId = lastUserId; }
    public String getLeaseOwner() { return leaseOwner; }
    public void setLeaseOwner(String leaseOwner) { this.leaseOwner = leaseOwner; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
