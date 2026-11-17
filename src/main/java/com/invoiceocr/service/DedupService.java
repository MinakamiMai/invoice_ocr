package com.invoiceocr.service;

import com.invoiceocr.domain.DuplicateType;
import com.invoiceocr.domain.Invoice;
import com.invoiceocr.parser.ParsedInvoice;
import com.invoiceocr.repo.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class DedupService {

    public record DuplicateCheck(boolean duplicate, DuplicateType type, String existingInvoiceId) {
        public static DuplicateCheck none() {
            return new DuplicateCheck(false, null, null);
        }
    }

    private final InvoiceRepository invoiceRepository;
    private final FingerprintService fingerprintService;
    private final SystemConfigService configService;
    private final JdbcTemplate jdbcTemplate;

    public DuplicateCheck check(ParsedInvoice invoice, String excludeInvoiceId) {
        if (invoice.getInvoiceCode() != null && invoice.getInvoiceNumber() != null) {
            Optional<Invoice> exact = invoiceRepository.findByInvoiceCodeAndInvoiceNumber(
                    invoice.getInvoiceCode(), invoice.getInvoiceNumber());
            if (exact.isPresent() && !exact.get().getId().equals(excludeInvoiceId)) {
                return new DuplicateCheck(true, DuplicateType.EXACT, exact.get().getId());
            }
        }
        if (configService.getBoolean(SystemConfigService.DEDUP_FALLBACK_ENABLED, true)) {
            String fallback = fingerprintService.fallback(invoice);
            if (fallback != null) {
                Optional<Invoice> existing = invoiceRepository.findFirstByFingerprintFallbackOrderByCreatedAtAsc(fallback);
                if (existing.isPresent() && !existing.get().getId().equals(excludeInvoiceId)) {
                    return new DuplicateCheck(true, DuplicateType.FINGERPRINT, existing.get().getId());
                }
            }
        }
        if (configService.getBoolean(SystemConfigService.DEDUP_FIELD_HASH_ENABLED, true)) {
            String fields = fingerprintService.fields(invoice);
            if (fields != null) {
                Optional<Invoice> existing = invoiceRepository.findFirstByFingerprintFieldsOrderByCreatedAtAsc(fields);
                if (existing.isPresent() && !existing.get().getId().equals(excludeInvoiceId)) {
                    return new DuplicateCheck(true, DuplicateType.FIELD_HASH, existing.get().getId());
                }
            }
        }
        return DuplicateCheck.none();
    }

    public DuplicateCheck check(Invoice invoice) {
        if (invoice.getInvoiceCode() != null && invoice.getInvoiceNumber() != null) {
            Optional<Invoice> exact = invoiceRepository.findByInvoiceCodeAndInvoiceNumber(
                    invoice.getInvoiceCode(), invoice.getInvoiceNumber());
            if (exact.isPresent() && !exact.get().getId().equals(invoice.getId())) {
                return new DuplicateCheck(true, DuplicateType.EXACT, exact.get().getId());
            }
        }
        if (configService.getBoolean(SystemConfigService.DEDUP_FALLBACK_ENABLED, true)) {
            String fallback = fingerprintService.fallback(invoice);
            if (fallback != null) {
                Optional<Invoice> existing = invoiceRepository.findFirstByFingerprintFallbackOrderByCreatedAtAsc(fallback);
                if (existing.isPresent() && !existing.get().getId().equals(invoice.getId())) {
                    return new DuplicateCheck(true, DuplicateType.FINGERPRINT, existing.get().getId());
                }
            }
        }
        if (configService.getBoolean(SystemConfigService.DEDUP_FIELD_HASH_ENABLED, true)) {
            String fields = fingerprintService.fields(invoice);
            if (fields != null) {
                Optional<Invoice> existing = invoiceRepository.findFirstByFingerprintFieldsOrderByCreatedAtAsc(fields);
                if (existing.isPresent() && !existing.get().getId().equals(invoice.getId())) {
                    return new DuplicateCheck(true, DuplicateType.FIELD_HASH, existing.get().getId());
                }
            }
        }
        return DuplicateCheck.none();
    }

    /**
     * Keeps the fallback dedup registry in sync after a reviewer changes fingerprint fields.
     */
    public void refreshFallbackRegistry(Invoice invoice) {
        String newKey = fingerprintService.fallback(invoice);
        String registryKey = newKey == null ? null : "FB:" + newKey;
        if (registryKey == null) {
            jdbcTemplate.update("DELETE FROM dedup_registry WHERE invoice_id = ?", invoice.getId());
            return;
        }
        jdbcTemplate.update("DELETE FROM dedup_registry WHERE invoice_id = ? AND dedup_key <> ?",
                invoice.getId(), registryKey);
        jdbcTemplate.update("INSERT IGNORE INTO dedup_registry(dedup_key, created_at) VALUES (?, NOW())",
                registryKey);
        jdbcTemplate.update("UPDATE dedup_registry SET invoice_id = ? WHERE dedup_key = ? AND invoice_id IS NULL",
                invoice.getId(), registryKey);
    }

    public void markExistingDuplicate(String invoiceId) {
        invoiceRepository.findById(invoiceId).ifPresent(existing -> {
            if (!existing.isDuplicateFlag()) {
                existing.setDuplicateFlag(true);
                existing.setUpdatedAt(LocalDateTime.now());
                invoiceRepository.save(existing);
            }
        });
    }
}
