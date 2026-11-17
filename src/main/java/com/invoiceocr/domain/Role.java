package com.invoiceocr.domain;

public enum Role {
    SUBMITTER("提交人员"),
    REVIEWER("财务复核人员"),
    ADMIN("系统管理员"),
    AUDITOR("审计查看者");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
