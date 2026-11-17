package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.domain.OcrTaskStatus;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.OcrTaskRepository;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadService {

    private final FileValidationService validationService;
    private final FileStorageService storageService;
    private final InvoiceRepository invoiceRepository;
    private final OcrTaskRepository taskRepository;
    private final InvoiceProcessingService processingService;
    private final InvoiceAssembler assembler;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public InvoiceDtos.UploadResponse upload(MultipartFile file, String invoiceTypeHint) {
        UserPrincipal principal = currentUser.require();
        FileValidationService.ValidatedFile validated = validationService.validate(file);
        String hint = normalizeTypeHint(invoiceTypeHint);

        Optional<Invoice> existingByFile = invoiceRepository.findFirstByFileHash(validated.fileHash());
        if (existingByFile.isPresent()) {
            Invoice existing = existingByFile.get();
            if (!currentUser.isPrivileged() && !existing.getOwnerId().equals(principal.id())) {
                throw ApiException.conflict("相同文件已被其他用户上传，请勿重复提交");
            }
            auditService.log("UPLOAD_DUPLICATE_FILE", "INVOICE", existing.getId(),
                    "文件内容重复，返回已有结果");
            return new InvoiceDtos.UploadResponse(existing.getId(), existing.getStatus().name(),
                    true, "FILE_HASH", null, "相同文件已识别过，直接返回已有结果",
                    assembler.toDetail(existing));
        }

        Optional<OcrTask> inFlight = taskRepository.findTopByFileHashAndStatusInOrderByCreatedAtDesc(
                validated.fileHash(), List.of(OcrTaskStatus.PENDING, OcrTaskStatus.RUNNING));
        if (inFlight.isPresent()) {
            OcrTask task = inFlight.get();
            if (!currentUser.isPrivileged() && !task.getOwnerId().equals(principal.id())) {
                throw ApiException.conflict("相同文件正在被其他用户处理，请勿重复提交");
            }
            return new InvoiceDtos.UploadResponse(task.getInvoiceId(), "PROCESSING", false, null, null,
                    "相同文件正在识别中，请稍后查询", null);
        }

        String invoiceId = UUID.randomUUID().toString();
        String fileRef = storageService.storeInvoiceFile(invoiceId, validated.extension(), validated.bytes());

        OcrTask task = new OcrTask();
        task.setInvoiceId(invoiceId);
        task.setOwnerId(principal.id());
        task.setOwnerUsername(principal.username());
        task.setFileHash(validated.fileHash());
        task.setFileRef(fileRef);
        task.setFileType(validated.extension());
        task.setFileSize(validated.size());
        task.setOriginalFilename(validated.originalFilename());
        task.setInvoiceTypeHint(hint);
        task.setStatus(OcrTaskStatus.PENDING);
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.save(task);

        auditService.log("UPLOAD", "INVOICE", invoiceId,
                "filename=" + validated.originalFilename() + ", size=" + validated.size()
                        + ", sha256=" + validated.fileHash());

        processingService.process(invoiceId, task.getId());
        return new InvoiceDtos.UploadResponse(invoiceId, "PROCESSING", false, null, null,
                "文件已接收，正在后台识别", null);
    }

    private String normalizeTypeHint(String invoiceTypeHint) {
        if (invoiceTypeHint == null || invoiceTypeHint.isBlank()) {
            return null;
        }
        InvoiceType type = InvoiceType.fromCode(invoiceTypeHint);
        if (type == null) {
            throw ApiException.badRequest("无法识别的发票类型提示：" + invoiceTypeHint);
        }
        return type.name();
    }
}
