package com.invoiceocr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoice")
@Getter
@Setter
@NoArgsConstructor
public class Invoice {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", length = 32)
    private InvoiceType invoiceType;

    @Column(name = "invoice_code", length = 20)
    private String invoiceCode;

    @Column(name = "invoice_number", length = 20)
    private String invoiceNumber;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "machine_number", length = 32)
    private String machineNumber;

    @Column(name = "buyer_name", length = 300)
    private String buyerName;

    @Column(name = "buyer_tax_id", length = 20)
    private String buyerTaxId;

    @Column(name = "buyer_address_phone", length = 500)
    private String buyerAddressPhone;

    @Column(name = "buyer_bank_account", length = 500)
    private String buyerBankAccount;

    @Column(name = "seller_name", length = 300)
    private String sellerName;

    @Column(name = "seller_tax_id", length = 20)
    private String sellerTaxId;

    @Column(name = "seller_address_phone", length = 500)
    private String sellerAddressPhone;

    @Column(name = "seller_bank_account", length = 500)
    private String sellerBankAccount;

    @Column(name = "total_amount", precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_tax", precision = 18, scale = 2)
    private BigDecimal totalTax;

    @Column(name = "grand_total", precision = 18, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "grand_total_cn", length = 100)
    private String grandTotalCn;

    @Column(name = "tax_rate", length = 32)
    private String taxRate;

    @Column(name = "check_code", length = 20)
    private String checkCode;

    @Column(length = 100)
    private String payee;

    @Column(name = "reviewer_name", length = 100)
    private String reviewerName;

    @Column(length = 100)
    private String drawer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private InvoiceStatus status;

    @Column(name = "duplicate_flag", nullable = false)
    private boolean duplicateFlag;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_type", length = 32)
    private DuplicateType duplicateType;

    @Column(name = "duplicate_of_id", length = 36)
    private String duplicateOfId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", length = 32)
    private VerificationStatus verificationStatus;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "needs_review_reason", length = 1000)
    private String needsReviewReason;

    @Column(name = "fingerprint_exact", length = 64)
    private String fingerprintExact;

    @Column(name = "fingerprint_fallback", length = 64)
    private String fingerprintFallback;

    @Column(name = "fingerprint_fields", length = 64)
    private String fingerprintFields;

    @Column(name = "field_confidence", columnDefinition = "json")
    private String fieldConfidence;

    @Column(name = "raw_file_ref", nullable = false, length = 512)
    private String rawFileRef;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "file_type", nullable = false, length = 16)
    private String fileType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
