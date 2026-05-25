package com.elebusiness.repository;

import com.elebusiness.model.entity.GenerationHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GenerationHistoryRepository extends JpaRepository<GenerationHistory, Long> {

    /** 全局按时间降序：历史抽屉默认显示最近的 */
    Page<GenerationHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按 sessionId 查（"只看当前会话"过滤） */
    Page<GenerationHistory> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    /** 按 mode 过滤 */
    Page<GenerationHistory> findByModeOrderByCreatedAtDesc(String mode, Pageable pageable);

    /** saveToGallery 成功时回填 savedPath：先按 outputPath 找历史条目 */
    Optional<GenerationHistory> findFirstByOutputPath(String outputPath);
}
