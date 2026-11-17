package com.invoiceocr.verify;

import com.invoiceocr.domain.VerificationStatus;

public record VerificationResult(VerificationStatus status, String channel, String summary) {
}
