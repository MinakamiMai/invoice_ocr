package com.invoiceocr.web;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.OcrTaskRepository;
import com.invoiceocr.service.FileStorageService;
import com.invoiceocr.service.SignedUrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class FileController {

    private final SignedUrlService signedUrlService;
    private final FileStorageService storageService;
    private final InvoiceRepository invoiceRepository;
    private final OcrTaskRepository taskRepository;

    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id,
                                         @RequestParam("expires") Long expires,
                                         @RequestParam("sig") String sig) {
        signedUrlService.verify(id, expires, sig);
        String ref;
        String type;
        Invoice invoice = invoiceRepository.findById(id).orElse(null);
        if (invoice != null) {
            ref = invoice.getRawFileRef();
            type = invoice.getFileType();
        } else {
            OcrTask task = taskRepository.findTopByInvoiceIdOrderByCreatedAtDesc(id)
                    .orElseThrow(() -> ApiException.notFound("文件不存在"));
            ref = task.getFileRef();
            type = task.getFileType();
        }
        byte[] content = storageService.read(ref);
        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(storageService.contentType(type));
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + id + "." + type + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new ByteArrayResource(content));
    }
}
