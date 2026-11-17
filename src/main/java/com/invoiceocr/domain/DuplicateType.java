package com.invoiceocr.domain;

public enum DuplicateType {
    EXACT("精确重复（发票代码+号码）"),
    FINGERPRINT("降级指纹重复（销售方+日期+价税合计）"),
    FIELD_HASH("字段哈希碰撞（金额+日期+购销方税号）");

    private final String label;

    DuplicateType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
