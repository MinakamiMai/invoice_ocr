package com.invoiceocr.ocr;

/**
 * A structured field returned directly by a commercial OCR engine.
 */
public record OcrFieldHint(String value, double confidence) {

    public OcrFieldHint {
        confidence = Math.max(0, Math.min(100, confidence));
    }
}
