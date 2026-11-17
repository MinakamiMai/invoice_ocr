package com.invoiceocr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.domain.AppUser;
import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceItem;
import com.invoiceocr.domain.InvoiceStatus;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.domain.OcrTaskStatus;
import com.invoiceocr.domain.ReviewRecord;
import com.invoiceocr.domain.VerificationRecord;
import com.invoiceocr.domain.VerificationStatus;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.repo.AppUserRepository;
import com.invoiceocr.repo.InvoiceItemRepository;
import com.invoiceocr.repo.OcrTaskRepository;
import com.invoiceocr.repo.ReviewRecordRepository;
import com.invoiceocr.repo.VerificationRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InvoiceAssembler {

    private final InvoiceItemRepository itemRepository;
    private final ReviewRecordRepository reviewRepository;
    private final VerificationRecordRepository verificationRepository;
    private final OcrTaskRepository taskRepository;
    private final AppUserRepository userRepository;
    private final SignedUrlService signedUrlService;
    private final ObjectMapper objectMapper;

    public InvoiceDtos.InvoiceDetail toDetail(Invoice invoice) {
        List<InvoiceDtos.ItemDto> items = itemRepository.findByInvoiceIdOrderByLineNoAsc(invoice.getId())
                .stream().map(this::toItemDto).toList();
        List<InvoiceDtos.ReviewRecordDto> reviews = reviewRepository
                .findByInvoiceIdOrderByReviewedAtAsc(invoice.getId())
                .stream().map(this::toReviewDto).toList();
        List<InvoiceDtos.VerificationDto> verifications = verificationRepository
                .findByInvoiceIdOrderByVerifiedAtDesc(invoice.getId())
                .stream().map(this::toVerificationDto).toList();
        OcrTask task = taskRepository.findTopByInvoiceIdOrderByCreatedAtDesc(invoice.getId()).orElse(null);

        return new InvoiceDtos.InvoiceDetail(
                invoice.getId(),
                invoice.getStatus().name(),
                invoice.getStatus().getLabel(),
                invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().name(),
                invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().getLabel(),
                invoice.getInvoiceCode(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getMachineNumber(),
                new InvoiceDtos.Party(invoice.getBuyerName(), invoice.getBuyerTaxId(),
                        invoice.getBuyerAddressPhone(), invoice.getBuyerBankAccount()),
                new InvoiceDtos.Party(invoice.getSellerName(), invoice.getSellerTaxId(),
                        invoice.getSellerAddressPhone(), invoice.getSellerBankAccount()),
                invoice.getTotalAmount(),
                invoice.getTotalTax(),
                invoice.getGrandTotal(),
                invoice.getGrandTotalCn(),
                invoice.getTaxRate(),
                invoice.getCheckCode(),
                invoice.getPayee(),
                invoice.getReviewerName(),
                invoice.getDrawer(),
                items,
                parseConfidence(invoice.getFieldConfidence()),
                invoice.isDuplicateFlag(),
                invoice.getDuplicateType() == null ? null : invoice.getDuplicateType().name(),
                invoice.getDuplicateOfId(),
                invoice.getVerificationStatus() == null ? null : invoice.getVerificationStatus().name(),
                invoice.getVerificationStatus() == null ? null : invoice.getVerificationStatus().getLabel(),
                invoice.getVerifiedAt(),
                invoice.getNeedsReviewReason(),
                reasons(invoice),
                username(invoice.getOwnerId()),
                invoice.getCreatedAt(),
                invoice.getUpdatedAt(),
                invoice.getOriginalFilename(),
                invoice.getFileHash(),
                invoice.getFileType(),
                invoice.getFileSize(),
                signedUrlService.previewUrl(invoice.getId()),
                task == null ? null : toTaskDto(task),
                reviews,
                verifications);
    }

    public InvoiceDtos.InvoiceListItem toListItem(Invoice invoice) {
        return new InvoiceDtos.InvoiceListItem(
                invoice.getId(),
                invoice.getStatus().name(),
                invoice.getStatus().getLabel(),
                invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().name(),
                invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().getLabel(),
                invoice.getInvoiceCode(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceDate(),
                invoice.getBuyerName(),
                invoice.getBuyerTaxId(),
                invoice.getSellerName(),
                invoice.getSellerTaxId(),
                invoice.getTotalAmount(),
                invoice.getTotalTax(),
                invoice.getGrandTotal(),
                invoice.getTaxRate(),
                invoice.getCheckCode(),
                invoice.isDuplicateFlag(),
                invoice.getDuplicateType() == null ? null : invoice.getDuplicateType().name(),
                invoice.getVerificationStatus() == null ? null : invoice.getVerificationStatus().name(),
                invoice.getVerificationStatus() == null ? null : invoice.getVerificationStatus().getLabel(),
                username(invoice.getOwnerId()),
                invoice.getCreatedAt(),
                reasons(invoice));
    }

    public InvoiceDtos.UploadStatus toUploadStatus(OcrTask task) {
        InvoiceStatus displayed;
        if (task.getStatus() == OcrTaskStatus.FAILED) {
            displayed = InvoiceStatus.OCR_FAILED;
        } else if (task.getStatus() == OcrTaskStatus.DUPLICATE) {
            displayed = InvoiceStatus.DUPLICATE;
        } else {
            displayed = InvoiceStatus.PROCESSING;
        }
        return new InvoiceDtos.UploadStatus(
                task.getInvoiceId(),
                displayed.name(),
                displayed.getLabel(),
                task.getStatus() == OcrTaskStatus.DUPLICATE
                        || (task.getDuplicateOfId() != null),
                task.getDuplicateType() == null ? null : task.getDuplicateType().name(),
                task.getDuplicateOfId(),
                switch (task.getStatus()) {
                    case DUPLICATE -> "该发票已存在，未重复入库";
                    case FAILED -> "识别失败，可重新识别";
                    case RUNNING -> "正在识别，请稍候";
                    case COMPLETED -> "处理完成";
                    case PENDING -> "已进入识别队列";
                },
                task.getErrorMessage(),
                task.getEngineUsed(),
                task.getCreatedAt(),
                task.getFinishedAt(),
                signedUrlService.previewUrl(task.getInvoiceId()));
    }

    public InvoiceDtos.TaskDto toTaskDto(OcrTask task) {
        return new InvoiceDtos.TaskDto(task.getId(),
                task.getStatus().name(),
                task.getStatus().getLabel(),
                task.getEngineUsed(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getErrorMessage(),
                task.getRawResultRef());
    }

    public InvoiceDtos.ItemDto toItemDto(InvoiceItem item) {
        return new InvoiceDtos.ItemDto(item.getLineNo(), item.getItemName(), item.getSpecification(),
                item.getUnit(), item.getQuantity(), item.getUnitPrice(), item.getAmount(),
                item.getTaxRate(), item.getTaxAmount());
    }

    public List<String> reasons(Invoice invoice) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (invoice.getNeedsReviewReason() != null && !invoice.getNeedsReviewReason().isBlank()) {
            for (String part : invoice.getNeedsReviewReason().split("[；;]")) {
                if (!part.isBlank()) {
                    reasons.add(part.trim());
                }
            }
        }
        if (invoice.isDuplicateFlag()) {
            reasons.add("查重命中：" + (invoice.getDuplicateType() == null ? "疑似重复" : invoice.getDuplicateType().getLabel()));
        }
        VerificationStatus verification = invoice.getVerificationStatus();
        if (verification != null && verification.needsAttention()) {
            reasons.add("验真结果：" + verification.getLabel());
        }
        if (invoice.getStatus() == InvoiceStatus.NEEDS_REVIEW && reasons.isEmpty()) {
            reasons.add("待人工复核");
        }
        return new ArrayList<>(reasons);
    }

    private InvoiceDtos.ReviewRecordDto toReviewDto(ReviewRecord record) {
        return new InvoiceDtos.ReviewRecordDto(record.getId(), record.getFieldName(), record.getOldValue(),
                record.getNewValue(), record.getReviewer(), record.getReviewedAt(),
                record.getConclusion().name(), record.getComment());
    }

    private InvoiceDtos.VerificationDto toVerificationDto(VerificationRecord record) {
        return new InvoiceDtos.VerificationDto(record.getId(), record.getChannel(),
                record.getResultStatus().name(), record.getResultStatus().getLabel(),
                record.getResultSummary(), record.getVerifiedAt());
    }

    public Map<String, Integer> parseConfidence(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String username(Long userId) {
        Optional<AppUser> user = userRepository.findById(userId);
        return user.map(AppUser::getUsername).orElse("user-" + userId);
    }
}
