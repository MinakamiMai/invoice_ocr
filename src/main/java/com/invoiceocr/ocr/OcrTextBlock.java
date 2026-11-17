package com.invoiceocr.ocr;

/**
 * A line of recognized text with a confidence value in the range 0-100 and optional bounding box.
 */
public record OcrTextBlock(String text, double confidence, int x, int y, int width, int height) {

    public OcrTextBlock {
        if (text == null) {
            text = "";
        }
        confidence = Math.max(0, Math.min(100, confidence));
    }

    public static OcrTextBlock line(String text, double confidence) {
        return new OcrTextBlock(text, confidence, 0, 0, 0, 0);
    }
}
