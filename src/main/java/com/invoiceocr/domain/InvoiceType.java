package com.invoiceocr.domain;

import java.util.Locale;

public enum InvoiceType {
    VAT_SPECIAL("增值税专用发票"),
    VAT_NORMAL("增值税普通发票"),
    VAT_E_NORMAL("增值税电子普通发票"),
    DIGITAL("全电发票（数电票）"),
    VAT_ROLL("增值税卷票"),
    OTHER("其他发票");

    private final String label;

    InvoiceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public static InvoiceType fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (InvoiceType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
            if (type.label.equals(code.trim())) {
                return type;
            }
        }
        return null;
    }
}
