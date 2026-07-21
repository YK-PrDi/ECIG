package com.elebusiness.repository;

import com.elebusiness.model.entity.Enterprise;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EnterpriseRepository extends JpaRepository<Enterprise, Long> {

    List<Enterprise> findAllByOrderByCreatedAtAsc();
}
