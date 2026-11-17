package com.invoiceocr.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceItem;
import com.invoiceocr.domain.InvoiceStatus;
import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.domain.ReviewConclusion;
import com.invoiceocr.domain.ReviewRecord;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.parser.FieldNames;
import com.invoiceocr.repo.InvoiceItemRepository;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.ReviewRecordRepository;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final Set<String> EDITABLE_FIELDS = Set.of(
            FieldNames.INVOICE_TYPE, FieldNames.INVOICE_CODE, FieldNames.INVOICE_NUMBER,
            FieldNames.INVOICE_DATE, FieldNames.MACHINE_NUMBER,
            FieldNames.BUYER_NAME, FieldNames.BUYER_TAX_ID, FieldNames.BUYER_ADDRESS_PHONE,
            FieldNames.BUYER_BANK_ACCOUNT, FieldNames.SELLER_NAME, FieldNames.SELLER_TAX_ID,
            FieldNames.SELLER_ADDRESS_PHONE, FieldNames.SELLER_BANK_ACCOUNT,
            FieldNames.TOTAL_AMOUNT, FieldNames.TOTAL_TAX, FieldNames.GRAND_TOTAL,
            FieldNames.GRAND_TOTAL_CN, FieldNames.TAX_RATE, FieldNames.CHECK_CODE,
            FieldNames.PAYEE, FieldNames.REVIEWER_NAME, FieldNames.DRAWER
    );

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final ReviewRecordRepository reviewRepository;
    private final FingerprintService fingerprintService;
    private final DedupService dedupService;
    private final InvoiceAssembler assembler;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    @Transactional
    public InvoiceDtos.ReviewResponse review(String invoiceId, InvoiceDtos.ReviewRequest request) {
        if (request == null) {
            throw ApiException.badRequest("复核请求不能为空");
        }
        ReviewConclusion conclusion = parseConclusion(request.conclusion());
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ApiException.notFound("发票不存在"));
        if (invoice.getStatus() == InvoiceStatus.PROCESSING) {
            throw ApiException.conflict("发票正在识别中，请稍后再复核");
        }
        UserPrincipal reviewer = currentUser.require();
        LocalDateTime now = LocalDateTime.now();
        List<String> changedFields = new ArrayList<>();
        Map<String, Integer> confidence = new LinkedHashMap<>(assembler.parseConfidence(invoice.getFieldConfidence()));

        if (request.fields() != null) {
            for (Map.Entry<String, String> entry : request.fields().entrySet()) {
                String field = entry.getKey();
                if (!EDITABLE_FIELDS.contains(field)) {
                    throw ApiException.badRequest("不允许修改字段：" + field);
                }
                String oldValue = currentValue(invoice, field);
                String newValue = trimToNull(entry.getValue());
                if (Objects.equals(oldValue, newValue)) {
                    continue;
                }
                applyField(invoice, field, newValue);
                confidence.put(field, 100);
                changedFields.add(field);
                saveReviewRecord(invoiceId, field, oldValue, newValue, reviewer.username(), now, conclusion,
                        request.comment());
            }
        }

        if (request.items() != null) {
            List<InvoiceDtos.ItemDto> oldItems = itemRepository.findByInvoiceIdOrderByLineNoAsc(invoiceId)
                    .stream().map(assembler::toItemDto).toList();
            String oldJson = toJson(oldItems);
            String newJson = toJson(request.items());
            if (!oldJson.equals(newJson)) {
                replaceItems(invoiceId, request.items());
                changedFields.add("items");
                saveReviewRecord(invoiceId, "items", oldJson, newJson, reviewer.username(), now, conclusion,
                        request.comment());
            }
        }

        invoice.setFingerprintExact(fingerprintService.exact(invoice));
        invoice.setFingerprintFallback(fingerprintService.fallback(invoice));
        invoice.setFingerprintFields(fingerprintService.fields(invoice));

        DedupService.DuplicateCheck duplicate = dedupService.check(invoice);
        if (duplicate.duplicate() && duplicate.type() == com.invoiceocr.domain.DuplicateType.EXACT) {
            throw ApiException.conflict("修改后的发票代码+号码与已有发票重复，不能保存");
        }
        invoice.setDuplicateFlag(duplicate.duplicate());
        invoice.setDuplicateType(duplicate.type());
        invoice.setDuplicateOfId(duplicate.existingInvoiceId());
        dedupService.refreshFallbackRegistry(invoice);

        InvoiceStatus oldStatus = invoice.getStatus();
        InvoiceStatus newStatus = switch (conclusion) {
            case PASS -> InvoiceStatus.CONFIRMED;
            case REJECT -> InvoiceStatus.REJECTED;
            case NEEDS_MORE_INFO -> InvoiceStatus.NEEDS_REVIEW;
        };
        invoice.setStatus(newStatus);
        if (conclusion == ReviewConclusion.PASS) {
            invoice.setNeedsReviewReason(duplicate.duplicate()
                    ? "查重命中：" + duplicate.type().getLabel() : null);
        } else if (conclusion == ReviewConclusion.REJECT) {
            invoice.setNeedsReviewReason(trimToNull(request.comment()) == null
                    ? "复核拒绝" : trimToNull(request.comment()));
        } else {
            invoice.setNeedsReviewReason(trimToNull(request.comment()) == null
                    ? "需进一步核实" : trimToNull(request.comment()));
        }
        invoice.setUpdatedAt(now);
        try {
            invoice.setFieldConfidence(objectMapper.writeValueAsString(confidence));
        } catch (Exception e) {
            throw ApiException.badRequest("字段置信度序列化失败");
        }

        saveReviewRecord(invoiceId, "conclusion", oldStatus.name(), newStatus.name(),
                reviewer.username(), now, conclusion, request.comment());
        try {
            invoiceRepository.saveAndFlush(invoice);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("修改后的发票代码+号码与已有发票重复，不能保存");
        }

        auditService.log("REVIEW", "INVOICE", invoiceId,
                "conclusion=" + conclusion + ", changed=" + changedFields
                        + ", duplicate=" + duplicate.duplicate());
        return new InvoiceDtos.ReviewResponse(invoiceId, newStatus.name(), changedFields,
                invoice.isDuplicateFlag(), invoice.getDuplicateType() == null ? null
                : invoice.getDuplicateType().name());
    }

    private void replaceItems(String invoiceId, List<InvoiceDtos.ItemDto> items) {
        itemRepository.deleteByInvoiceId(invoiceId);
        itemRepository.flush();
        int index = 1;
        for (InvoiceDtos.ItemDto dto : items) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoiceId(invoiceId);
            item.setLineNo(dto.lineNo() == null ? index : dto.lineNo());
            item.setItemName(dto.itemName());
            item.setSpecification(dto.specification());
            item.setUnit(dto.unit());
            item.setQuantity(dto.quantity());
            item.setUnitPrice(dto.unitPrice());
            item.setAmount(dto.amount());
            item.setTaxRate(dto.taxRate());
            item.setTaxAmount(dto.taxAmount());
            itemRepository.save(item);
            index++;
        }
    }

    private void saveReviewRecord(String invoiceId, String field, String oldValue, String newValue,
                                  String reviewer, LocalDateTime now, ReviewConclusion conclusion,
                                  String comment) {
        ReviewRecord record = new ReviewRecord();
        record.setInvoiceId(invoiceId);
        record.setFieldName(field);
        record.setOldValue(oldValue);
        record.setNewValue(newValue);
        record.setReviewer(reviewer);
        record.setReviewedAt(now);
        record.setConclusion(conclusion);
        record.setComment(comment == null ? null : (comment.length() > 1000 ? comment.substring(0, 1000) : comment));
        reviewRepository.save(record);
    }

    private void applyField(Invoice invoice, String field, String value) {
        switch (field) {
            case FieldNames.INVOICE_TYPE -> invoice.setInvoiceType(parseType(value));
            case FieldNames.INVOICE_CODE -> invoice.setInvoiceCode(truncate(cleanCompact(value), 20));
            case FieldNames.INVOICE_NUMBER -> invoice.setInvoiceNumber(truncate(cleanCompact(value), 20));
            case FieldNames.INVOICE_DATE -> invoice.setInvoiceDate(parseDate(value));
            case FieldNames.MACHINE_NUMBER -> invoice.setMachineNumber(truncate(value, 32));
            case FieldNames.BUYER_NAME -> invoice.setBuyerName(truncate(value, 300));
            case FieldNames.BUYER_TAX_ID -> invoice.setBuyerTaxId(truncate(cleanTaxId(value), 20));
            case FieldNames.BUYER_ADDRESS_PHONE -> invoice.setBuyerAddressPhone(truncate(value, 500));
            case FieldNames.BUYER_BANK_ACCOUNT -> invoice.setBuyerBankAccount(truncate(value, 500));
            case FieldNames.SELLER_NAME -> invoice.setSellerName(truncate(value, 300));
            case FieldNames.SELLER_TAX_ID -> invoice.setSellerTaxId(truncate(cleanTaxId(value), 20));
            case FieldNames.SELLER_ADDRESS_PHONE -> invoice.setSellerAddressPhone(truncate(value, 500));
            case FieldNames.SELLER_BANK_ACCOUNT -> invoice.setSellerBankAccount(truncate(value, 500));
            case FieldNames.TOTAL_AMOUNT -> invoice.setTotalAmount(parseAmount(value));
            case FieldNames.TOTAL_TAX -> invoice.setTotalTax(parseAmount(value));
            case FieldNames.GRAND_TOTAL -> invoice.setGrandTotal(parseAmount(value));
            case FieldNames.GRAND_TOTAL_CN -> invoice.setGrandTotalCn(truncate(value, 100));
            case FieldNames.TAX_RATE -> invoice.setTaxRate(truncate(value, 32));
            case FieldNames.CHECK_CODE -> invoice.setCheckCode(truncate(cleanCompact(value), 20));
            case FieldNames.PAYEE -> invoice.setPayee(truncate(value, 100));
            case FieldNames.REVIEWER_NAME -> invoice.setReviewerName(truncate(value, 100));
            case FieldNames.DRAWER -> invoice.setDrawer(truncate(value, 100));
            default -> throw ApiException.badRequest("不允许修改字段：" + field);
        }
    }

    private String currentValue(Invoice invoice, String field) {
        return switch (field) {
            case FieldNames.INVOICE_TYPE -> invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().name();
            case FieldNames.INVOICE_CODE -> invoice.getInvoiceCode();
            case FieldNames.INVOICE_NUMBER -> invoice.getInvoiceNumber();
            case FieldNames.INVOICE_DATE -> invoice.getInvoiceDate() == null ? null
                    : invoice.getInvoiceDate().toString();
            case FieldNames.MACHINE_NUMBER -> invoice.getMachineNumber();
            case FieldNames.BUYER_NAME -> invoice.getBuyerName();
            case FieldNames.BUYER_TAX_ID -> invoice.getBuyerTaxId();
            case FieldNames.BUYER_ADDRESS_PHONE -> invoice.getBuyerAddressPhone();
            case FieldNames.BUYER_BANK_ACCOUNT -> invoice.getBuyerBankAccount();
            case FieldNames.SELLER_NAME -> invoice.getSellerName();
            case FieldNames.SELLER_TAX_ID -> invoice.getSellerTaxId();
            case FieldNames.SELLER_ADDRESS_PHONE -> invoice.getSellerAddressPhone();
            case FieldNames.SELLER_BANK_ACCOUNT -> invoice.getSellerBankAccount();
            case FieldNames.TOTAL_AMOUNT -> money(invoice.getTotalAmount());
            case FieldNames.TOTAL_TAX -> money(invoice.getTotalTax());
            case FieldNames.GRAND_TOTAL -> money(invoice.getGrandTotal());
            case FieldNames.GRAND_TOTAL_CN -> invoice.getGrandTotalCn();
            case FieldNames.TAX_RATE -> invoice.getTaxRate();
            case FieldNames.CHECK_CODE -> invoice.getCheckCode();
            case FieldNames.PAYEE -> invoice.getPayee();
            case FieldNames.REVIEWER_NAME -> invoice.getReviewerName();
            case FieldNames.DRAWER -> invoice.getDrawer();
            default -> null;
        };
    }

    private ReviewConclusion parseConclusion(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("请选择复核结论");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        try {
            return ReviewConclusion.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return switch (value.trim()) {
                case "通过" -> ReviewConclusion.PASS;
                case "拒绝" -> ReviewConclusion.REJECT;
                case "需进一步核实" -> ReviewConclusion.NEEDS_MORE_INFO;
                default -> throw ApiException.badRequest("未知复核结论：" + value);
            };
        }
    }

    private InvoiceType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        InvoiceType type = InvoiceType.fromCode(value);
        if (type == null) {
            throw ApiException.badRequest("未知发票类型：" + value);
        }
        return type;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.trim();
        try {
            if (compact.matches("\\d{8}")) {
                return LocalDate.of(Integer.parseInt(compact.substring(0, 4)),
                        Integer.parseInt(compact.substring(4, 6)), Integer.parseInt(compact.substring(6, 8)));
            }
            Matcher chinese = Pattern.compile("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})").matcher(compact);
            if (chinese.find()) {
                return LocalDate.of(Integer.parseInt(chinese.group(1)),
                        Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
            }
            return LocalDate.parse(compact);
        } catch (Exception e) {
            throw ApiException.badRequest("日期格式错误：" + value);
        }
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.replaceAll("[￥¥,\\s]", "")).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("金额格式错误：" + value);
        }
    }

    private String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String cleanCompact(String value) {
        return value == null ? null : value.replaceAll("\\s+", "");
    }

    private String cleanTaxId(String value) {
        return value == null ? null : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String toJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            return json.length() > 8000 ? json.substring(0, 8000) : json;
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
