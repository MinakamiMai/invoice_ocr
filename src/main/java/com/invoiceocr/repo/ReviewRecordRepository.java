package com.invoiceocr.repo;

import com.invoiceocr.domain.ReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRecordRepository extends JpaRepository<ReviewRecord, Long> {

    List<ReviewRecord> findByInvoiceIdOrderByReviewedAtAsc(String invoiceId);
}
