package com.invoiceocr.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InvoiceQuery {

    private String id;
    private String invoiceCode;
    private String invoiceNumber;
    private LocalDate invoiceDateFrom;
    private LocalDate invoiceDateTo;
    private String sellerName;
    private String sellerTaxId;
    private String buyerName;
    private String buyerTaxId;
    private BigDecimal amountMin;
    private BigDecimal amountMax;
    private String invoiceType;
    private String status;
    private Boolean duplicateFlag;
    private String verificationStatus;
    private LocalDate uploadFrom;
    private LocalDate uploadTo;
    private String keyword;
    private Integer page = 0;
    private Integer size = 20;
    private String sortBy = "createdAt";
    private String sortDir = "desc";
}
