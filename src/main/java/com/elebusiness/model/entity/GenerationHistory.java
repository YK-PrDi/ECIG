package com.elebusiness.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 生图历史：一条 = 一次成功生成（图或视频）。
 * Phase 2 引入。建表 DDL 由 hibernate ddl-auto=update 自动生成。
 *
 * 设计选型（用户决策记录）：
 * - mode：standard / custom / ecommerce / kaipin / video
 * - prompt：最终送给 agent 的完整 prompt（已经过 PromptCondenser 压缩 / 拼装）
 * - configJson：模式专属配置 snapshot（卖点 key、品类路径、kpFocus、自定义文字等），
 *   完整版恢复用 — 用户在历史里点 "🔄 用此参数重生" 时把整个 UI 状态还原
 * - refPathsJson：JSON 数组，存 .history-refs/{generationId}/N.{ext} 的相对路径列表
 *   不存 .uploads/.temp-output 的路径（那两个会被 cleaner 删）
 * - outputPath：临时归档的图片绝对路径（.temp-output 下，可能 2h 后被清理）
 * - savedPath：用户点 💾 后的永久路径（./生成结果/<日期>/...），未保存时 null
 * - saved：boolean，等价于 savedPath != null，但显式存方便查询
 */
@Entity
@Table(name = "generation_history",
       indexes = {
           @Index(name = "idx_gen_created", columnList = "createdAt"),
           @Index(name = "idx_gen_mode", columnList = "mode"),
           @Index(name = "idx_gen_session", columnList = "sessionId")
       })
public class GenerationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 跟 ConversationHistory 同一个 sessionId，方便查"这个会话生了哪些图" */
    @Column(length = 64)
    private String sessionId;

    /** standard / custom / ecommerce / kaipin / video */
    @Column(nullable = false, length = 32)
    private String mode;

    /** 最终送给 agent 的 prompt（已压缩 / 已拼装） */
    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    /** 模式专属配置的 JSON snapshot，完整版恢复用 */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String configJson;

    @Column(nullable = false, length = 64)
    private String agentId;

    /** JSON 数组：[".history-refs/abc123/0.jpg", ".history-refs/abc123/1.jpg"] */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String refPathsJson;

    /** .temp-output 下的临时绝对路径，2h 后可能已被清理 */
    @Column(length = 1024)
    private String outputPath;

    /** 永久路径：用户点 💾 后填，未保存时 null */
    @Column(length = 1024)
    private String savedPath;

    @Column(nullable = false)
    private boolean saved = false;

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
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getRefPathsJson() { return refPathsJson; }
    public void setRefPathsJson(String refPathsJson) { this.refPathsJson = refPathsJson; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public String getSavedPath() { return savedPath; }
    public void setSavedPath(String savedPath) { this.savedPath = savedPath; }
    public boolean isSaved() { return saved; }
    public void setSaved(boolean saved) { this.saved = saved; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
