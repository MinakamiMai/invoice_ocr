package com.invoiceocr.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class AdminDtos {

    private AdminDtos() {
    }

    public record ConfigItem(String key,
                             String value,
                             boolean encrypted,
                             boolean masked,
                             String description,
                             LocalDateTime updatedAt,
                             String updatedBy) {
    }

    public record ConfigListResponse(List<ConfigItem> items,
                                     List<EngineInfo> engines,
                                     String currentEngine,
                                     String currentVerifyChannel) {
    }

    public record ConfigUpdateRequest(Map<String, String> values) {
    }

    public record EngineInfo(String name, String description, boolean available, String detail) {
    }
}
