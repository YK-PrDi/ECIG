package com.elebusiness.repository;

import com.elebusiness.model.entity.CompanyAsset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyAssetRepository extends JpaRepository<CompanyAsset, Long> {

    Page<CompanyAsset> findByEnterpriseIdOrderByCreatedAtDesc(Long enterpriseId, Pageable pageable);

    Page<CompanyAsset> findByEnterpriseIdAndTypeOrderByCreatedAtDesc(Long enterpriseId, String type, Pageable pageable);

    /** 数据迁移用：找出还没归属企业的资产 */
    List<CompanyAsset> findByEnterpriseIdIsNull();
}
