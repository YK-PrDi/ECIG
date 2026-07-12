package com.elebusiness.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_order",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_payment_order_no", columnNames = "orderNo"),
           @UniqueConstraint(name = "uk_payment_order_idempotency", columnNames = "idempotencyKey")
       },
       indexes = {
           @Index(name = "idx_payment_order_user", columnList = "userId"),
           @Index(name = "idx_payment_order_status", columnList = "status"),
           @Index(name = "idx_payment_order_created", columnList = "createdAt"),
           @Index(name = "idx_payment_order_user_id", columnList = "userId, id"),
           @Index(name = "idx_payment_order_user_created_id", columnList = "userId, createdAt, id"),
           @Index(name = "idx_payment_order_user_status_provider_id", columnList = "userId, status, provider, id"),
           @Index(name = "idx_payment_order_status_user", columnList = "status, userId"),
           @Index(name = "idx_payment_order_status_provider_id", columnList = "status, provider, id"),
           @Index(name = "idx_payment_order_status_user_id", columnList = "status, userId, id")
       })
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long points;

    @Column(nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 16)
    private String currency = "CNY";

    @Column(nullable = false, length = 32)
    private String provider = "manual";

    @Column(length = 128)
    private String providerOrderNo;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(length = 128)
    private String idempotencyKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime paidAt;

    @Version
    private Long version;

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
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public long getPoints() { return points; }
    public void setPoints(long points) { this.points = points; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderOrderNo() { return providerOrderNo; }
    public void setProviderOrderNo(String providerOrderNo) { this.providerOrderNo = providerOrderNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
