package com.invoiceocr.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "verification_record")
@Getter
@Setter
@NoArgsConstructor
public class VerificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(nullable = false, length = 64)
    private String channel;

    @Column(name = "request_payload_hash", nullable = false, length = 64)
    private String requestPayloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, length = 32)
    private VerificationStatus resultStatus;

    @Column(name = "result_summary", columnDefinition = "text")
    private String resultSummary;

    @Column(name = "verified_at", nullable = false)
    private LocalDateTime verifiedAt = LocalDateTime.now();
}
