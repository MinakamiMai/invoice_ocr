package com.invoiceocr.repo;

import com.invoiceocr.domain.VerificationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VerificationRecordRepository extends JpaRepository<VerificationRecord, Long> {

    List<VerificationRecord> findByInvoiceIdOrderByVerifiedAtDesc(String invoiceId);
}
