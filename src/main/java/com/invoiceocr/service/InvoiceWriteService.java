package com.invoiceocr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.domain.DuplicateType;
import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceItem;
import com.invoiceocr.domain.InvoiceStatus;
import com.invoiceocr.parser.ParsedInvoice;
import com.invoiceocr.repo.InvoiceItemRepository;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persists a parsed invoice atomically together with dedup safeguards.
 * Exact duplicates are never stored twice thanks to the unique (invoice_code, invoice_number) index.
 * Fallback duplicates take a row lock in dedup_registry so concurrent uploads cannot both pass.
 */
@Slf4j
@Service
public class InvoiceWriteService {

    public record PersistResult(Invoice invoice, DedupService.DuplicateCheck duplicate) {
    }

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final FingerprintService fingerprintService;
    private final DedupService dedupService;
    private final SystemConfigService configService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public InvoiceWriteService(InvoiceRepository invoiceRepository,
                               InvoiceItemRepository itemRepository,
                               FingerprintService fingerprintService,
                               DedupService dedupService,
                               SystemConfigService configService,
                               TransactionTemplate transactionTemplate,
                               JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper) {
        this.invoiceRepository = invoiceRepository;
        this.itemRepository = itemRepository;
        this.fingerprintService = fingerprintService;
        this.dedupService = dedupService;
        this.configService = configService;
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public PersistResult persist(Invoice invoice, List<InvoiceItem> items, ParsedInvoice parsed,
                                 String excludeInvoiceId) {
        invoice.setFingerprintExact(fingerprintService.exact(parsed));
        invoice.setFingerprintFallback(fingerprintService.fallback(parsed));
        invoice.setFingerprintFields(fingerprintService.fields(parsed));

        DedupService.DuplicateCheck preCheck = dedupService.check(parsed, excludeInvoiceId);
        if (preCheck.duplicate() && preCheck.type() == DuplicateType.EXACT) {
            dedupService.markExistingDuplicate(preCheck.existingInvoiceId());
            return new PersistResult(null, preCheck);
        }

        try {
            return transactionTemplate.execute(status -> doPersist(invoice, items, parsed, preCheck));
        } catch (DataIntegrityViolationException e) {
            Optional<Invoice> existing = invoice.getInvoiceCode() == null || invoice.getInvoiceNumber() == null
                    ? Optional.empty()
                    : invoiceRepository.findByInvoiceCodeAndInvoiceNumber(
                    invoice.getInvoiceCode(), invoice.getInvoiceNumber());
            if (existing.isPresent() && !existing.get().getId().equals(invoice.getId())) {
                dedupService.markExistingDuplicate(existing.get().getId());
                log.info("Concurrent exact duplicate detected for invoice {}", invoice.getId());
                return new PersistResult(null,
                        new DedupService.DuplicateCheck(true, DuplicateType.EXACT, existing.get().getId()));
            }
            throw e;
        }
    }

    private PersistResult doPersist(Invoice invoice, List<InvoiceItem> items, ParsedInvoice parsed,
                                    DedupService.DuplicateCheck preCheck) {
        DedupService.DuplicateCheck duplicate = preCheck;
        String fallbackFingerprint = invoice.getFingerprintFallback();
        boolean fallbackEnabled = configService.getBoolean(SystemConfigService.DEDUP_FALLBACK_ENABLED, true);
        String fallbackKey = fallbackFingerprint == null ? null : "FB:" + fallbackFingerprint;

        if (fallbackEnabled && fallbackKey != null) {
            jdbcTemplate.update("INSERT IGNORE INTO dedup_registry(dedup_key, created_at) VALUES (?, NOW())",
                    fallbackKey);
            List<String> owners = jdbcTemplate.queryForList(
                    "SELECT invoice_id FROM dedup_registry WHERE dedup_key = ? FOR UPDATE",
                    String.class, fallbackKey);
            String owner = owners.isEmpty() ? null : owners.get(0);
            if (owner != null && !owner.equals(invoice.getId()) && duplicate.type() != DuplicateType.EXACT) {
                duplicate = new DedupService.DuplicateCheck(true, DuplicateType.FINGERPRINT, owner);
            }
        }

        Optional<Invoice> existingInvoice = invoiceRepository.findById(invoice.getId());
        invoice.setStatus(InvoiceStatus.NEEDS_REVIEW);
        invoice.setDuplicateFlag(duplicate.duplicate());
        invoice.setDuplicateType(duplicate.type());
        invoice.setDuplicateOfId(duplicate.existingInvoiceId());
        invoice.setCreatedAt(existingInvoice.map(Invoice::getCreatedAt).orElseGet(LocalDateTime::now));
        invoice.setUpdatedAt(LocalDateTime.now());
        try {
            invoice.setFieldConfidence(objectMapper.writeValueAsString(parsed.getFieldConfidence()));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SERIALIZATION_ERROR",
                    "字段置信度序列化失败");
        }
        if (existingInvoice.isPresent()) {
            itemRepository.deleteByInvoiceId(invoice.getId());
            itemRepository.flush();
        }
        invoiceRepository.saveAndFlush(invoice);
        for (InvoiceItem item : items) {
            item.setInvoiceId(invoice.getId());
        }
        itemRepository.saveAll(items);

        if (fallbackEnabled && fallbackKey != null) {
            String registryOwner = duplicate.type() == DuplicateType.FINGERPRINT
                    && duplicate.existingInvoiceId() != null
                    ? duplicate.existingInvoiceId() : invoice.getId();
            jdbcTemplate.update("UPDATE dedup_registry SET invoice_id = ? WHERE dedup_key = ? AND invoice_id IS NULL",
                    registryOwner, fallbackKey);
        }
        return new PersistResult(invoice, duplicate);
    }
}
