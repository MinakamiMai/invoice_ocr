package com.invoiceocr.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Development/demo engine. It never fabricates invoice data: it returns an empty result so that
 * the normal low-confidence / needs-review path is exercised without a native OCR runtime.
 * Text-based PDF/OFD files are handled by the document extractors before reaching this client.
 */
@Slf4j
@Component
public class MockOcrClient implements InvoiceOcrClient {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String displayName() {
        return "Mock 引擎（仅演示流程，不产生识别文本）";
    }

    @Override
    public OcrRawResult recognize(byte[] fileBytes, String fileType) {
        log.info("Mock OCR invoked for {} bytes of type {}; returning empty result", fileBytes.length, fileType);
        return new OcrRawResult("mock", "", List.of(), java.util.Map.of());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean prefersOriginalImage() {
        return true;
    }
}
