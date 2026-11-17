package com.invoiceocr.ocr;

import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses the optimal path for each document type:
 * PDF/OFD text layer first, then rendered images + engine. Engine-specific preprocessing
 * (original image for commercial APIs, binarization/deskew for local engines) is applied
 * inside {@link OcrEngineService} so that fallback engines get their own preparation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrOrchestrator {

    private static final int RENDER_DPI = 200;

    private final PdfDocumentExtractor pdfExtractor;
    private final OfdDocumentExtractor ofdExtractor;
    private final OcrEngineService engineService;
    private final SystemConfigService configService;

    public OcrRawResult recognize(byte[] bytes, String fileType) {
        String type = fileType == null ? "" : fileType.toLowerCase();
        return switch (type) {
            case "pdf" -> recognizePdf(bytes);
            case "ofd" -> recognizeOfd(bytes);
            case "jpg", "jpeg", "png", "bmp" -> recognizeImage(bytes, type);
            default -> throw new OcrException("不支持的文件类型：" + fileType);
        };
    }

    private OcrRawResult recognizePdf(byte[] bytes) {
        String text = pdfExtractor.extractText(bytes);
        int minChars = configService.getInt(SystemConfigService.PDF_MIN_TEXT_CHARS, 20);
        if (text != null && text.replaceAll("\\s+", "").length() >= minChars) {
            return OcrRawResult.text("pdf-text", text);
        }
        int maxPages = configService.getInt(SystemConfigService.PDF_MAX_PAGES, 3);
        List<byte[]> pages = pdfExtractor.renderPages(bytes, maxPages, RENDER_DPI);
        List<OcrRawResult> results = new ArrayList<>();
        for (byte[] page : pages) {
            results.add(engineService.recognize(page, "png"));
        }
        return combine(results, "pdf-scan");
    }

    private OcrRawResult recognizeOfd(byte[] bytes) {
        OfdDocumentExtractor.OfdContent content = ofdExtractor.extract(bytes);
        int minChars = configService.getInt(SystemConfigService.PDF_MIN_TEXT_CHARS, 20);
        if (content.text() != null && content.text().replaceAll("\\s+", "").length() >= minChars) {
            return OcrRawResult.text("ofd-text", content.text());
        }
        int maxImages = configService.getInt(SystemConfigService.OFD_MAX_IMAGES, 3);
        List<OcrRawResult> results = new ArrayList<>();
        for (int i = 0; i < Math.min(content.images().size(), Math.max(maxImages, 1)); i++) {
            results.add(engineService.recognize(content.images().get(i), "png"));
        }
        return combine(results, "ofd-scan");
    }

    private OcrRawResult recognizeImage(byte[] bytes, String type) {
        return engineService.recognize(bytes, type);
    }

    private OcrRawResult combine(List<OcrRawResult> results, String fallbackEngine) {
        if (results.isEmpty()) {
            return OcrRawResult.empty(fallbackEngine);
        }
        Set<String> engines = new LinkedHashSet<>();
        results.forEach(result -> engines.add(result.engine()));
        String engineName = String.join("+", engines);
        return OcrRawResult.combine(engineName, results);
    }
}
