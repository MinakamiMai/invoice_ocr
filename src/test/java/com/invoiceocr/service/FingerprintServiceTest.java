package com.invoiceocr.service;

import com.invoiceocr.parser.ParsedInvoice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class FingerprintServiceTest {

    private final FingerprintService service = new FingerprintService();

    @Test
    void buildsDeterministicFingerprints() {
        ParsedInvoice invoice = invoice();
        String exact = service.exact(invoice);
        String fallback = service.fallback(invoice);
        String fields = service.fields(invoice);

        assertNotNull(exact);
        assertNotNull(fallback);
        assertNotNull(fields);
        assertEquals(exact, service.exact(invoice));
        assertEquals(fallback, service.fallback(invoice));
        assertEquals(fields, service.fields(invoice));
        assertNotEquals(exact, fallback);
    }

    @Test
    void fingerprintsChangeWithKeyFields() {
        ParsedInvoice invoice = invoice();
        String fallback = service.fallback(invoice);
        invoice.setGrandTotal(new BigDecimal("107.00"));
        assertNotEquals(fallback, service.fallback(invoice));
    }

    @Test
    void missingFieldsYieldNullFingerprint() {
        ParsedInvoice invoice = invoice();
        invoice.setInvoiceNumber(null);
        assertNull(service.exact(invoice));
        invoice.setSellerTaxId(null);
        assertNull(service.fallback(invoice));
        assertNull(service.fields(invoice));
    }

    private ParsedInvoice invoice() {
        ParsedInvoice invoice = new ParsedInvoice();
        invoice.setInvoiceCode("011002200111");
        invoice.setInvoiceNumber("12345678");
        invoice.setInvoiceDate(LocalDate.of(2026, 11, 10));
        invoice.setSellerTaxId("91310115MA1KXYZ12A");
        invoice.setBuyerTaxId("91110108MA01ABCD2X");
        invoice.setGrandTotal(new BigDecimal("1060.00"));
        return invoice;
    }
}
