package com.invoiceocr.repo;

import com.invoiceocr.domain.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, String>, JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findFirstByFileHash(String fileHash);

    boolean existsByFileHash(String fileHash);

    Optional<Invoice> findByInvoiceCodeAndInvoiceNumber(String invoiceCode, String invoiceNumber);

    Optional<Invoice> findFirstByFingerprintFallbackOrderByCreatedAtAsc(String fingerprintFallback);

    Optional<Invoice> findFirstByFingerprintFieldsOrderByCreatedAtAsc(String fingerprintFields);
}
