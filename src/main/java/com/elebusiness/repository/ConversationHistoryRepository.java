package com.elebusiness.repository;

import com.elebusiness.model.entity.ConversationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationHistoryRepository extends JpaRepository<ConversationHistory, Long> {

    /** 按 sessionId 查，时间降序 */
    Page<ConversationHistory> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /** 不限 session，全局查最近的（跨会话查看） */
    Page<ConversationHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 删除某 session 全部对话（用户清空当前会话历史） */
    long deleteBySessionId(String sessionId);
}
