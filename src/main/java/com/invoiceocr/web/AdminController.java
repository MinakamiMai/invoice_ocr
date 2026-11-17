package com.invoiceocr.web;

import com.invoiceocr.dto.AdminDtos;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.ocr.OcrEngineService;
import com.invoiceocr.service.AuditService;
import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final SystemConfigService configService;
    private final OcrEngineService engineService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    @GetMapping("/config")
    public AdminDtos.ConfigListResponse config() {
        return new AdminDtos.ConfigListResponse(
                configService.listMasked(),
                engineService.listEngines(),
                configService.get(SystemConfigService.OCR_ENGINE, "mock"),
                configService.get(SystemConfigService.VERIFY_CHANNEL, "mock"));
    }

    @PutMapping("/config")
    public AdminDtos.ConfigListResponse update(@RequestBody AdminDtos.ConfigUpdateRequest request) {
        UserPrincipal principal = currentUser.require();
        configService.update(request.values(), principal.username());
        auditService.log("CONFIG_UPDATE", "SYSTEM", null,
                "keys=" + (request.values() == null ? List.of() : request.values().keySet()));
        return config();
    }
}
