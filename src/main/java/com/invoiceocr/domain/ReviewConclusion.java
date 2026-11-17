package com.invoiceocr.domain;

public enum ReviewConclusion {
    PASS("通过"),
    REJECT("拒绝"),
    NEEDS_MORE_INFO("需进一步核实");

    private final String label;

    ReviewConclusion(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
