package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.parser.ParsedInvoice;
import com.invoiceocr.util.HashUtil;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class FingerprintService {

    public String exact(ParsedInvoice invoice) {
        return exact(invoice.getInvoiceCode(), invoice.getInvoiceNumber());
    }

    public String exact(Invoice invoice) {
        return exact(invoice.getInvoiceCode(), invoice.getInvoiceNumber());
    }

    private String exact(String code, String number) {
        if (isBlank(code) || isBlank(number)) {
            return null;
        }
        return HashUtil.sha256Hex("EXACT|" + normalize(code) + "|" + normalize(number));
    }

    public String fallback(ParsedInvoice invoice) {
        return fallback(invoice.getSellerTaxId(), invoice.getInvoiceDate() == null ? null
                : invoice.getInvoiceDate().toString(), invoice.getGrandTotal());
    }

    public String fallback(Invoice invoice) {
        return fallback(invoice.getSellerTaxId(), invoice.getInvoiceDate() == null ? null
                : invoice.getInvoiceDate().toString(), invoice.getGrandTotal());
    }

    private String fallback(String sellerTaxId, String invoiceDate, BigDecimal grandTotal) {
        if (isBlank(sellerTaxId) || isBlank(invoiceDate) || grandTotal == null) {
            return null;
        }
        return HashUtil.sha256Hex("FB|" + normalize(sellerTaxId).toUpperCase() + "|" + invoiceDate + "|"
                + money(grandTotal));
    }

    public String fields(ParsedInvoice invoice) {
        return fields(invoice.getGrandTotal(), invoice.getInvoiceDate() == null ? null
                : invoice.getInvoiceDate().toString(), invoice.getBuyerTaxId(), invoice.getSellerTaxId());
    }

    public String fields(Invoice invoice) {
        return fields(invoice.getGrandTotal(), invoice.getInvoiceDate() == null ? null
                : invoice.getInvoiceDate().toString(), invoice.getBuyerTaxId(), invoice.getSellerTaxId());
    }

    private String fields(BigDecimal grandTotal, String invoiceDate, String buyerTaxId, String sellerTaxId) {
        if (grandTotal == null || isBlank(invoiceDate) || isBlank(buyerTaxId) || isBlank(sellerTaxId)) {
            return null;
        }
        return HashUtil.sha256Hex("FIELDS|" + money(grandTotal) + "|" + invoiceDate + "|"
                + normalize(buyerTaxId).toUpperCase() + "|" + normalize(sellerTaxId).toUpperCase());
    }

    private String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
