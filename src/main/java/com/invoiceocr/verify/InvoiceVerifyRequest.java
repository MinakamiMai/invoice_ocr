package com.invoiceocr.verify;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Six-element verification request required by invoice verification platforms.
 */
public record InvoiceVerifyRequest(String invoiceCode,
                                   String invoiceNumber,
                                   LocalDate invoiceDate,
                                   String checkCode,
                                   BigDecimal amountWithoutTax,
                                   String invoiceType,
                                   BigDecimal grandTotal) {
}
