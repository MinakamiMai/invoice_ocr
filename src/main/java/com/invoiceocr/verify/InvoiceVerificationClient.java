package com.invoiceocr.verify;

public interface InvoiceVerificationClient {

    String name();

    VerificationResult verify(InvoiceVerifyRequest request);
}
