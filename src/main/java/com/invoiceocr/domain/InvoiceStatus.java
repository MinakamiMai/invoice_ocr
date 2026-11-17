package com.invoiceocr.domain;

public enum InvoiceStatus {
    PROCESSING("处理中"),
    COMPLETED("已识别"),
    NEEDS_REVIEW("待复核"),
    CONFIRMED("已确认"),
    REJECTED("已拒绝"),
    OCR_FAILED("识别失败"),
    DUPLICATE("重复上传");

    private final String label;

    InvoiceStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
