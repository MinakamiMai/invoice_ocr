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
@Table(name = "ocr_task")
@Getter
@Setter
@NoArgsConstructor
public class OcrTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false, length = 36)
    private String invoiceId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "owner_username", length = 64)
    private String ownerUsername;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "file_ref", length = 512)
    private String fileRef;

    @Column(name = "file_type", length = 16)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "invoice_type_hint", length = 32)
    private String invoiceTypeHint;

    @Column(name = "engine_used", length = 64)
    private String engineUsed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OcrTaskStatus status;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "raw_result_ref", length = 512)
    private String rawResultRef;

    @Column(name = "duplicate_of_id", length = 36)
    private String duplicateOfId;

    @Enumerated(EnumType.STRING)
    @Column(name = "duplicate_type", length = 32)
    private DuplicateType duplicateType;

    @Column(length = 500)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
