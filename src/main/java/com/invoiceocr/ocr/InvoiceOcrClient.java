package com.invoiceocr.ocr;

/**
 * Replaceable OCR engine adapter. Implementations must not change business layer behavior.
 */
public interface InvoiceOcrClient {

    String name();

    String displayName();

    OcrRawResult recognize(byte[] fileBytes, String fileType);

    default boolean available() {
        return true;
    }

    /**
     * Commercial APIs and mock engines read the original image best. Local engines (Tesseract)
     * need binarization/deskew preprocessing. Returning true skips that preprocessing and only
     * normalizes the image when it exceeds the API size limits.
     */
    default boolean prefersOriginalImage() {
        return false;
    }
}
