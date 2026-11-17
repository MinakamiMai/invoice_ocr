package com.invoiceocr.parser;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ParsedItem {
    private int lineNo;
    private String itemName;
    private String specification;
    private String unit;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal amount;
    private String taxRate;
    private BigDecimal taxAmount;
}
