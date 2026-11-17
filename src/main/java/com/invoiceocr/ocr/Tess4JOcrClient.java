package com.invoiceocr.ocr;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.Word;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tesseract adapter. Requires a local Tesseract installation and chi_sim traineddata.
 * Configure the tessdata parent directory via ocr.tesseract.datapath.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Tess4JOcrClient implements InvoiceOcrClient {

    private final SystemConfigService configService;
    private final AppProperties appProperties;

    @Override
    public String name() {
        return "tesseract";
    }

    @Override
    public String displayName() {
        return "Tesseract（Tess4J，开源离线引擎）";
    }

    @Override
    public OcrRawResult recognize(byte[] fileBytes, String fileType) {
        String datapath = datapath();
        if (datapath.isBlank()) {
            throw new OcrException("未配置 Tesseract tessdata 目录（ocr.tesseract.datapath）");
        }
        String language = language();
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(fileBytes));
        } catch (Exception e) {
            throw new OcrException("无法读取待识别图片", e);
        }
        if (image == null) {
            throw new OcrException("不支持的图片格式");
        }
        Tesseract tesseract = new Tesseract();
        try {
            tesseract.setDatapath(datapath);
            tesseract.setLanguage(language);
            tesseract.setPageSegMode(6);
            tesseract.setOcrEngineMode(1);
            List<Word> lines = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE);
            List<OcrTextBlock> blocks = new ArrayList<>();
            if (lines != null) {
                lines.sort(Comparator.comparingInt((Word word) -> word.getBoundingBox().y)
                        .thenComparingInt(word -> word.getBoundingBox().x));
                for (Word line : lines) {
                    if (line.getText() == null || line.getText().isBlank()) {
                        continue;
                    }
                    var box = line.getBoundingBox();
                    blocks.add(new OcrTextBlock(line.getText().trim(), line.getConfidence(),
                            box.x, box.y, box.width, box.height));
                }
            }
            String text = blocks.stream().map(OcrTextBlock::text)
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            return new OcrRawResult("tesseract", text, blocks, java.util.Map.of());
        } catch (Exception e) {
            throw new OcrException("Tesseract 识别失败（请确认已安装 Tesseract 与 "
                    + language + " 语言包）：" + e.getMessage(), e);
        }
    }

    @Override
    public boolean available() {
        return !datapath().isBlank() && java.nio.file.Files.isDirectory(java.nio.file.Path.of(datapath()));
    }

    private String datapath() {
        String configured = configService.get("ocr.tesseract.datapath", "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return appProperties.getOcr().getTesseract().getDatapath() == null
                ? "" : appProperties.getOcr().getTesseract().getDatapath().trim();
    }

    private String language() {
        String configured = configService.get("ocr.tesseract.language", "");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return appProperties.getOcr().getTesseract().getLanguage();
    }

}
