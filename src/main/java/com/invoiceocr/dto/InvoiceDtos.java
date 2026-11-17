package com.invoiceocr.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class InvoiceDtos {

    private InvoiceDtos() {
    }

    public record UploadResponse(String invoiceId,
                                 String status,
                                 boolean duplicateFlag,
                                 String duplicateType,
                                 String duplicateOfInvoiceId,
                                 String message,
                                 InvoiceDetail existing) {
    }

    public record UploadStatus(String id,
                               String status,
                               String statusLabel,
                               boolean duplicateFlag,
                               String duplicateType,
                               String duplicateOfInvoiceId,
                               String message,
                               String errorMessage,
                               String engine,
                               LocalDateTime createdAt,
                               LocalDateTime finishedAt,
                               String previewUrl) {
    }

    public record InvoiceListItem(String id,
                                  String status,
                                  String statusLabel,
                                  String invoiceType,
                                  String invoiceTypeLabel,
                                  String invoiceCode,
                                  String invoiceNumber,
                                  LocalDate invoiceDate,
                                  String buyerName,
                                  String buyerTaxId,
                                  String sellerName,
                                  String sellerTaxId,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalTax,
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grandTotal,
                                  String taxRate,
                                  String checkCode,
                                  boolean duplicateFlag,
                                  String duplicateType,
                                  String verificationStatus,
                                  String verificationStatusLabel,
                                  String ownerUsername,
                                  LocalDateTime createdAt,
                                  List<String> reviewReasons) {
    }

    public record InvoiceDetail(String id,
                                String status,
                                String statusLabel,
                                String invoiceType,
                                String invoiceTypeLabel,
                                String invoiceCode,
                                String invoiceNumber,
                                LocalDate invoiceDate,
                                String machineNumber,
                                Party buyer,
                                Party seller,
                                @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAmount,
                                @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalTax,
                                @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grandTotal,
                                String grandTotalCn,
                                String taxRate,
                                String checkCode,
                                String payee,
                                String reviewerName,
                                String drawer,
                                List<ItemDto> items,
                                Map<String, Integer> fieldConfidence,
                                boolean duplicateFlag,
                                String duplicateType,
                                String duplicateOfInvoiceId,
                                String verificationStatus,
                                String verificationStatusLabel,
                                LocalDateTime verifiedAt,
                                String needsReviewReason,
                                List<String> reviewReasons,
                                String ownerUsername,
                                LocalDateTime createdAt,
                                LocalDateTime updatedAt,
                                String originalFilename,
                                String fileHash,
                                String fileType,
                                long fileSize,
                                String previewUrl,
                                TaskDto task,
                                List<ReviewRecordDto> reviews,
                                List<VerificationDto> verifications) {
    }

    public record Party(String name, String taxId, String addressPhone, String bankAccount) {
    }

    public record ItemDto(Integer lineNo,
                          String itemName,
                          String specification,
                          String unit,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal quantity,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
                          String taxRate,
                          @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal taxAmount) {
    }

    public record TaskDto(Long id,
                          String status,
                          String statusLabel,
                          String engine,
                          LocalDateTime startedAt,
                          LocalDateTime finishedAt,
                          String errorMessage,
                          String rawResultRef) {
    }

    public record ReviewRecordDto(Long id,
                                  String fieldName,
                                  String oldValue,
                                  String newValue,
                                  String reviewer,
                                  LocalDateTime reviewedAt,
                                  String conclusion,
                                  String comment) {
    }

    public record VerificationDto(Long id,
                                  String channel,
                                  String status,
                                  String statusLabel,
                                  String summary,
                                  LocalDateTime verifiedAt) {
    }

    public record ReviewRequest(Map<String, String> fields,
                                List<ItemDto> items,
                                String conclusion,
                                String comment) {
    }

    public record ReviewResponse(String id,
                                 String status,
                                 List<String> changedFields,
                                 boolean duplicateFlag,
                                 String duplicateType) {
    }

    public record VerifyResponse(String invoiceId,
                                 String status,
                                 String statusLabel,
                                 String summary,
                                 LocalDateTime verifiedAt) {
    }
}
