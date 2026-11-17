package com.invoiceocr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceItem;
import com.invoiceocr.domain.InvoiceStatus;
import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.domain.OcrTaskStatus;
import com.invoiceocr.ocr.OcrOrchestrator;
import com.invoiceocr.ocr.OcrRawResult;
import com.invoiceocr.parser.FieldNames;
import com.invoiceocr.parser.InvoiceParser;
import com.invoiceocr.parser.ParsedInvoice;
import com.invoiceocr.parser.ParsedItem;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.OcrTaskRepository;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceProcessingService {

    private final OcrTaskRepository taskRepository;
    private final InvoiceRepository invoiceRepository;
    private final FileStorageService storageService;
    private final OcrOrchestrator orchestrator;
    private final InvoiceParser parser;
    private final InvoiceWriteService writeService;
    private final SystemConfigService configService;
    private final VerificationService verificationService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Async("ocrExecutor")
    public void process(String invoiceId, long taskId) {
        OcrTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("OCR task {} disappeared before processing", taskId);
            return;
        }
        if (task.getStatus() != OcrTaskStatus.PENDING) {
            log.info("OCR task {} is already {}, skipping duplicate execution", taskId, task.getStatus());
            return;
        }
        task.setStatus(OcrTaskStatus.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskRepository.save(task);

        OcrRawResult raw = null;
        try {
            byte[] bytes = storageService.read(task.getFileRef());
            raw = orchestrator.recognize(bytes, task.getFileType());
            task.setEngineUsed(raw.engine());
            String rawRef = storageService.storeOcrResult(invoiceId, taskId,
                    objectMapper.writeValueAsBytes(raw));
            task.setRawResultRef(rawRef);

            ParsedInvoice parsed = parser.parse(raw, task.getInvoiceTypeHint());
            Invoice invoice = buildInvoice(task, parsed);
            boolean existingInvoice = invoiceRepository.existsById(invoiceId);
            InvoiceWriteService.PersistResult result = writeService.persist(invoice, buildItems(parsed),
                    parsed, existingInvoice ? invoiceId : null);

            if (result.invoice() == null) {
                task.setStatus(OcrTaskStatus.DUPLICATE);
                task.setDuplicateOfId(result.duplicate().existingInvoiceId());
                task.setDuplicateType(result.duplicate().type());
                task.setMessage("该发票已存在，未重复入库");
                task.setFinishedAt(LocalDateTime.now());
                taskRepository.save(task);
                if (existingInvoice) {
                    invoiceRepository.findById(invoiceId).ifPresent(existing -> {
                        existing.setStatus(InvoiceStatus.NEEDS_REVIEW);
                        existing.setNeedsReviewReason("重新识别后发现该发票与已有发票重复");
                        existing.setUpdatedAt(LocalDateTime.now());
                        invoiceRepository.save(existing);
                    });
                }
                auditService.logAs(task.getOwnerId(), task.getOwnerUsername(), null, "OCR_DUPLICATE",
                        "INVOICE", invoiceId, null,
                        "duplicateOf=" + result.duplicate().existingInvoiceId()
                                + ", type=" + result.duplicate().type());
                return;
            }

            Invoice saved = result.invoice();
            Assessment assessment = assess(parsed, raw, result.duplicate());
            saved.setStatus(assessment.status());
            saved.setNeedsReviewReason(assessment.reasonText());
            saved.setUpdatedAt(LocalDateTime.now());
            invoiceRepository.save(saved);

            task.setStatus(OcrTaskStatus.COMPLETED);
            task.setFinishedAt(LocalDateTime.now());
            task.setMessage(assessment.status() == InvoiceStatus.NEEDS_REVIEW
                    ? "识别完成，已进入人工复核" : "识别完成");
            taskRepository.save(task);

            auditService.logAs(task.getOwnerId(), task.getOwnerUsername(), null, "OCR_COMPLETED",
                    "INVOICE", invoiceId, null,
                    "engine=" + raw.engine() + ", status=" + assessment.status()
                            + ", duplicate=" + result.duplicate().duplicate());

            verificationService.maybeAutoVerify(saved, assessment.status() == InvoiceStatus.NEEDS_REVIEW);
        } catch (Exception e) {
            log.error("OCR processing failed for invoice {} task {}", invoiceId, taskId, e);
            task.setStatus(OcrTaskStatus.FAILED);
            task.setFinishedAt(LocalDateTime.now());
            task.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 2000));
            if (raw != null) {
                task.setEngineUsed(raw.engine());
            }
            taskRepository.save(task);
            auditService.logAs(task.getOwnerId(), task.getOwnerUsername(), null, "OCR_FAILED",
                    "INVOICE", invoiceId, null, task.getErrorMessage());
        }
    }

    /**
     * Creates a new OCR task for an existing invoice or a failed upload.
     */
    public OcrTask queueReprocess(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        OcrTask previous = taskRepository.findTopByInvoiceIdOrderByCreatedAtDesc(invoiceId)
                .orElseThrow(() -> ApiException.notFound("识别任务不存在"));
        if (invoice != null) {
            if (invoice.getStatus() == InvoiceStatus.CONFIRMED || invoice.getStatus() == InvoiceStatus.REJECTED) {
                throw ApiException.conflict("已确认或已拒绝的发票不能重新识别");
            }
            if (invoice.getStatus() == InvoiceStatus.PROCESSING) {
                throw ApiException.conflict("发票正在识别中，请勿重复提交");
            }
        } else if (previous.getStatus() != OcrTaskStatus.FAILED) {
            throw ApiException.conflict("当前任务不在可重试状态");
        }
        OcrTask task = new OcrTask();
        task.setInvoiceId(invoiceId);
        task.setOwnerId(previous.getOwnerId());
        task.setOwnerUsername(previous.getOwnerUsername());
        task.setFileHash(previous.getFileHash());
        task.setFileRef(previous.getFileRef());
        task.setFileType(previous.getFileType());
        task.setFileSize(previous.getFileSize());
        task.setOriginalFilename(previous.getOriginalFilename());
        task.setInvoiceTypeHint(previous.getInvoiceTypeHint());
        task.setStatus(OcrTaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.save(task);
        if (invoice != null) {
            invoice.setStatus(InvoiceStatus.PROCESSING);
            invoice.setUpdatedAt(LocalDateTime.now());
            invoiceRepository.save(invoice);
        }
        auditService.log("REPROCESS", "INVOICE", invoiceId, "taskId=" + task.getId());
        return task;
    }

    private Invoice buildInvoice(OcrTask task, ParsedInvoice parsed) {
        Invoice invoice = new Invoice();
        invoice.setId(task.getInvoiceId());
        invoice.setOwnerId(task.getOwnerId());
        invoice.setInvoiceType(parsed.getInvoiceType());
        invoice.setInvoiceCode(parsed.getInvoiceCode());
        invoice.setInvoiceNumber(parsed.getInvoiceNumber());
        invoice.setInvoiceDate(parsed.getInvoiceDate());
        invoice.setMachineNumber(parsed.getMachineNumber());
        invoice.setBuyerName(parsed.getBuyerName());
        invoice.setBuyerTaxId(parsed.getBuyerTaxId());
        invoice.setBuyerAddressPhone(parsed.getBuyerAddressPhone());
        invoice.setBuyerBankAccount(parsed.getBuyerBankAccount());
        invoice.setSellerName(parsed.getSellerName());
        invoice.setSellerTaxId(parsed.getSellerTaxId());
        invoice.setSellerAddressPhone(parsed.getSellerAddressPhone());
        invoice.setSellerBankAccount(parsed.getSellerBankAccount());
        invoice.setTotalAmount(parsed.getTotalAmount());
        invoice.setTotalTax(parsed.getTotalTax());
        invoice.setGrandTotal(parsed.getGrandTotal());
        invoice.setGrandTotalCn(parsed.getGrandTotalCn());
        invoice.setTaxRate(parsed.getTaxRate());
        invoice.setCheckCode(parsed.getCheckCode());
        invoice.setPayee(parsed.getPayee());
        invoice.setReviewerName(parsed.getReviewerName());
        invoice.setDrawer(parsed.getDrawer());
        invoice.setRawFileRef(task.getFileRef());
        invoice.setOriginalFilename(task.getOriginalFilename());
        invoice.setFileHash(task.getFileHash());
        invoice.setFileType(task.getFileType());
        invoice.setFileSize(task.getFileSize() == null ? 0 : task.getFileSize());
        invoice.setStatus(InvoiceStatus.NEEDS_REVIEW);
        return invoice;
    }

    private List<InvoiceItem> buildItems(ParsedInvoice parsed) {
        List<InvoiceItem> items = new ArrayList<>();
        for (ParsedItem parsedItem : parsed.getItems()) {
            InvoiceItem item = new InvoiceItem();
            item.setLineNo(parsedItem.getLineNo());
            item.setItemName(parsedItem.getItemName());
            item.setSpecification(parsedItem.getSpecification());
            item.setUnit(parsedItem.getUnit());
            item.setQuantity(parsedItem.getQuantity());
            item.setUnitPrice(parsedItem.getUnitPrice());
            item.setAmount(parsedItem.getAmount());
            item.setTaxRate(parsedItem.getTaxRate());
            item.setTaxAmount(parsedItem.getTaxAmount());
            items.add(item);
        }
        return items;
    }

    private Assessment assess(ParsedInvoice parsed, OcrRawResult raw,
                              DedupService.DuplicateCheck duplicate) {
        Set<String> reasons = new LinkedHashSet<>();
        if (raw == null || !raw.hasText()) {
            reasons.add("未识别到有效文本");
        }
        if (parsed.getInvoiceNumber() == null) {
            reasons.add("未识别到发票号码");
        }
        if (parsed.getInvoiceDate() == null) {
            reasons.add("未识别到开票日期");
        }
        if (parsed.getSellerName() == null) {
            reasons.add("未识别到销售方名称");
        }
        if (parsed.getBuyerName() == null) {
            reasons.add("未识别到购买方名称");
        }
        if (parsed.getGrandTotal() == null) {
            reasons.add("未识别到价税合计");
        }
        if (parsed.getInvoiceType() != InvoiceType.DIGITAL && parsed.getInvoiceCode() == null) {
            reasons.add("未识别到发票代码");
        }
        if (parsed.getInvoiceType() == InvoiceType.OTHER) {
            reasons.add("未能确定发票类型");
        }
        reasons.addAll(parsed.getWarnings());

        int threshold = configService.getInt(SystemConfigService.CONFIDENCE_THRESHOLD, 80);
        for (Map.Entry<String, Integer> entry : parsed.getFieldConfidence().entrySet()) {
            if (entry.getValue() != null && entry.getValue() < threshold
                    && !isMissingValue(parsed, entry.getKey())) {
                String label = FieldNames.LABELS.getOrDefault(entry.getKey(), entry.getKey());
                reasons.add(label + "置信度低（" + entry.getValue() + "）");
            }
        }
        if (duplicate.duplicate()) {
            reasons.add("查重命中：" + (duplicate.type() == null ? "疑似重复" : duplicate.type().getLabel()));
        }
        InvoiceStatus status = reasons.isEmpty() ? InvoiceStatus.COMPLETED : InvoiceStatus.NEEDS_REVIEW;
        return new Assessment(status, new ArrayList<>(reasons));
    }

    private boolean isMissingValue(ParsedInvoice parsed, String field) {
        return switch (field) {
            case FieldNames.INVOICE_TYPE -> parsed.getInvoiceType() == null;
            case FieldNames.INVOICE_CODE -> parsed.getInvoiceCode() == null;
            case FieldNames.INVOICE_NUMBER -> parsed.getInvoiceNumber() == null;
            case FieldNames.INVOICE_DATE -> parsed.getInvoiceDate() == null;
            case FieldNames.MACHINE_NUMBER -> parsed.getMachineNumber() == null;
            case FieldNames.BUYER_NAME -> parsed.getBuyerName() == null;
            case FieldNames.BUYER_TAX_ID -> parsed.getBuyerTaxId() == null;
            case FieldNames.BUYER_ADDRESS_PHONE -> parsed.getBuyerAddressPhone() == null;
            case FieldNames.BUYER_BANK_ACCOUNT -> parsed.getBuyerBankAccount() == null;
            case FieldNames.SELLER_NAME -> parsed.getSellerName() == null;
            case FieldNames.SELLER_TAX_ID -> parsed.getSellerTaxId() == null;
            case FieldNames.SELLER_ADDRESS_PHONE -> parsed.getSellerAddressPhone() == null;
            case FieldNames.SELLER_BANK_ACCOUNT -> parsed.getSellerBankAccount() == null;
            case FieldNames.TOTAL_AMOUNT -> parsed.getTotalAmount() == null;
            case FieldNames.TOTAL_TAX -> parsed.getTotalTax() == null;
            case FieldNames.GRAND_TOTAL -> parsed.getGrandTotal() == null;
            case FieldNames.GRAND_TOTAL_CN -> parsed.getGrandTotalCn() == null;
            case FieldNames.TAX_RATE -> parsed.getTaxRate() == null;
            case FieldNames.CHECK_CODE -> parsed.getCheckCode() == null;
            case FieldNames.PAYEE -> parsed.getPayee() == null;
            case FieldNames.REVIEWER_NAME -> parsed.getReviewerName() == null;
            case FieldNames.DRAWER -> parsed.getDrawer() == null;
            default -> false;
        };
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record Assessment(InvoiceStatus status, List<String> reasons) {
        String reasonText() {
            if (reasons.isEmpty()) {
                return null;
            }
            String text = String.join("；", reasons);
            return text.length() > 1000 ? text.substring(0, 1000) : text;
        }
    }
}
