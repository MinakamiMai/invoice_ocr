package com.invoiceocr.domain;

public enum OcrTaskStatus {
    PENDING("排队中"),
    RUNNING("识别中"),
    COMPLETED("已完成"),
    FAILED("失败"),
    DUPLICATE("重复发票");

    private final String label;

    OcrTaskStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
