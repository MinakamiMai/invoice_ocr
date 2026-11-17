package com.invoiceocr.web;

import com.invoiceocr.domain.AuditLog;
import com.invoiceocr.dto.AuditDtos;
import com.invoiceocr.dto.AuditQuery;
import com.invoiceocr.dto.CommonDtos;
import com.invoiceocr.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public CommonDtos.PageResponse<AuditDtos.AuditLogDto> list(@ModelAttribute AuditQuery query) {
        Page<AuditLog> page = auditService.query(query);
        return CommonDtos.PageResponse.of(page, log -> new AuditDtos.AuditLogDto(
                log.getId(), log.getUsername(), log.getRole(), log.getAction(), log.getObjectType(),
                log.getObjectId(), log.getIp(), log.getDetail(), log.getCreatedAt()));
    }
}
