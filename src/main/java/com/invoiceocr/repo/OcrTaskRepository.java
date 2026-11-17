package com.invoiceocr.repo;

import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.domain.OcrTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OcrTaskRepository extends JpaRepository<OcrTask, Long> {

    Optional<OcrTask> findTopByInvoiceIdOrderByCreatedAtDesc(String invoiceId);

    Optional<OcrTask> findTopByFileHashAndStatusInOrderByCreatedAtDesc(String fileHash,
                                                                      Collection<OcrTaskStatus> statuses);

    List<OcrTask> findByInvoiceIdOrderByCreatedAtDesc(String invoiceId);

    Optional<OcrTask> findTopByInvoiceIdAndStatusInOrderByCreatedAtDesc(String invoiceId,
                                                                        Collection<OcrTaskStatus> statuses);
}
