package com.elebusiness.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 对话历史：一行 = 一条聊天消息（user 或 ai 气泡）。
 * Phase 2 引入。建表 DDL 由 hibernate ddl-auto=update 自动生成。
 *
 * 设计选型：
 * - sessionId 由前端生成（uuid），同一浏览器开启期间保持不变；关闭浏览器换新（用户决策 1 = A）
 * - content 用 TEXT 存原文，包括卖点描述、bubbleText、AI 思考链等任意长度文本
 * - 不做任何"软删除"flag，让用户在历史 UI 删除条目就真删
 */
@Entity
@Table(name = "conversation_history",
       indexes = {
           @Index(name = "idx_conv_session", columnList = "sessionId"),
           @Index(name = "idx_conv_created", columnList = "createdAt")
       })
public class ConversationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String sessionId;

    /** "user" / "ai"，跟前端 appendMessage 第一参数对齐 */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息正文（可能很长：电商 headline / 卖点 prompt / AI 思考链等） */
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** standard / custom / ecommerce / kaipin / video，可空（welcome 阶段消息） */
    @Column(length = 32)
    private String mode;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
