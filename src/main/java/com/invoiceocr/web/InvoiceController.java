package com.invoiceocr.web;

import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.dto.CommonDtos;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.dto.InvoiceQuery;
import com.invoiceocr.service.ExportService;
import com.invoiceocr.service.InvoiceProcessingService;
import com.invoiceocr.service.InvoiceQueryService;
import com.invoiceocr.service.ReviewService;
import com.invoiceocr.service.UploadService;
import com.invoiceocr.service.VerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final UploadService uploadService;
    private final InvoiceQueryService queryService;
    private final ReviewService reviewService;
    private final VerificationService verificationService;
    private final InvoiceProcessingService processingService;
    private final ExportService exportService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('SUBMITTER','REVIEWER','ADMIN')")
    public ResponseEntity<InvoiceDtos.UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "invoiceType", required = false) String invoiceType) {
        InvoiceDtos.UploadResponse response = uploadService.upload(file, invoiceType);
        if (response.existing() != null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id) {
        return queryService.get(id);
    }

    @GetMapping
    public CommonDtos.PageResponse<InvoiceDtos.InvoiceListItem> list(@ModelAttribute InvoiceQuery query) {
        return queryService.search(query);
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('REVIEWER')")
    public InvoiceDtos.ReviewResponse review(@PathVariable String id,
                                              @RequestBody InvoiceDtos.ReviewRequest request) {
        return reviewService.review(id, request);
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public InvoiceDtos.VerifyResponse verify(@PathVariable String id) {
        return verificationService.verifyManually(id);
    }

    @PostMapping("/{id}/reprocess")
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public ResponseEntity<InvoiceDtos.UploadResponse> reprocess(@PathVariable String id) {
        OcrTask task = processingService.queueReprocess(id);
        processingService.process(id, task.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new InvoiceDtos.UploadResponse(id, "PROCESSING", false, null, null,
                        "已重新提交识别", null));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@ModelAttribute InvoiceQuery query,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(required = false) String columns) {
        ExportService.ExportFile file = exportService.export(query, format, columns);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.content().length))
                .body(file.content());
    }

}
