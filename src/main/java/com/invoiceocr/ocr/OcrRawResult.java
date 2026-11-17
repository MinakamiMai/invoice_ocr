package com.invoiceocr.ocr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-independent OCR result. Text layers (PDF/OFD) and commercial APIs are normalized into this shape.
 */
public record OcrRawResult(String engine,
                           String fullText,
                           List<OcrTextBlock> blocks,
                           Map<String, OcrFieldHint> structuredFields) {

    public OcrRawResult {
        if (blocks == null) {
            blocks = List.of();
        }
        if (structuredFields == null) {
            structuredFields = Map.of();
        }
        if (fullText == null) {
            fullText = blocks.stream().map(OcrTextBlock::text).reduce((a, b) -> a + "\n" + b).orElse("");
        }
    }

    public static OcrRawResult text(String engine, String text) {
        List<OcrTextBlock> blocks = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    blocks.add(OcrTextBlock.line(line, 100));
                }
            }
        }
        return new OcrRawResult(engine, text == null ? "" : text, blocks, Map.of());
    }

    public static OcrRawResult empty(String engine) {
        return new OcrRawResult(engine, "", List.of(), Map.of());
    }

    public static OcrRawResult combine(String engine, List<OcrRawResult> results) {
        StringBuilder text = new StringBuilder();
        List<OcrTextBlock> blocks = new ArrayList<>();
        Map<String, OcrFieldHint> hints = new LinkedHashMap<>();
        for (OcrRawResult result : results) {
            if (result == null) {
                continue;
            }
            if (!result.fullText().isBlank()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(result.fullText());
            }
            blocks.addAll(result.blocks());
            result.structuredFields().forEach(hints::putIfAbsent);
        }
        return new OcrRawResult(engine, text.toString(), blocks, hints);
    }

    public boolean hasText() {
        return fullText != null && !fullText.isBlank();
    }
}
