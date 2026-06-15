package com.elebusiness.repository;

import com.elebusiness.model.entity.KaiPinMaterial;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KaiPinMaterialRepository extends JpaRepository<KaiPinMaterial, Long> {
    Page<KaiPinMaterial> findAllByOrderByCreatedAtDesc(Pageable pageable);
    List<KaiPinMaterial> findByIdIn(List<Long> ids);
}
