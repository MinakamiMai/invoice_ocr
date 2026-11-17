package com.invoiceocr.repo;

import com.invoiceocr.domain.SystemConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SystemConfigRepository extends JpaRepository<SystemConfigEntity, String> {

    List<SystemConfigEntity> findAllByOrderByConfigKeyAsc();
}
