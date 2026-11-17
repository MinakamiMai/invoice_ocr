package com.invoiceocr.dto;

import java.time.LocalDateTime;

public final class AuditDtos {

    private AuditDtos() {
    }

    public record AuditLogDto(Long id,
                              String username,
                              String role,
                              String action,
                              String objectType,
                              String objectId,
                              String ip,
                              String detail,
                              LocalDateTime createdAt) {
    }
}
