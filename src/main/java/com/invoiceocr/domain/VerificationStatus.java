package com.invoiceocr.domain;

public enum VerificationStatus {
    CONSISTENT("一致"),
    INCONSISTENT("不一致"),
    NOT_FOUND("查无此票"),
    INTERFACE_ERROR("接口异常");

    private final String label;

    VerificationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static VerificationStatus fromExternal(String value) {
        if (value == null || value.isBlank()) {
            return INTERFACE_ERROR;
        }
        String v = value.trim().toUpperCase();
        return switch (v) {
            case "CONSISTENT", "MATCH", "一致", "0", "SUCCESS" -> CONSISTENT;
            case "INCONSISTENT", "MISMATCH", "不一致", "1" -> INCONSISTENT;
            case "NOT_FOUND", "NOTFOUND", "查无此票", "2" -> NOT_FOUND;
            default -> INTERFACE_ERROR;
        };
    }

    public boolean needsAttention() {
        return this == INCONSISTENT || this == NOT_FOUND || this == INTERFACE_ERROR;
    }
}
