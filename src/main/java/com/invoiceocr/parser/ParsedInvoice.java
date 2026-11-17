package com.invoiceocr.parser;

import com.invoiceocr.domain.InvoiceType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ParsedInvoice {

    private InvoiceType invoiceType;
    private String invoiceCode;
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String machineNumber;
    private String buyerName;
    private String buyerTaxId;
    private String buyerAddressPhone;
    private String buyerBankAccount;
    private String sellerName;
    private String sellerTaxId;
    private String sellerAddressPhone;
    private String sellerBankAccount;
    private BigDecimal totalAmount;
    private BigDecimal totalTax;
    private BigDecimal grandTotal;
    private String grandTotalCn;
    private String taxRate;
    private String checkCode;
    private String payee;
    private String reviewerName;
    private String drawer;
    private List<ParsedItem> items = new ArrayList<>();
    private Map<String, Integer> fieldConfidence = new LinkedHashMap<>();
    private List<String> warnings = new ArrayList<>();

    public void confidence(String field, double value) {
        fieldConfidence.put(field, (int) Math.round(Math.max(0, Math.min(100, value))));
    }

    public Integer confidenceOf(String field) {
        return fieldConfidence.get(field);
    }
}
