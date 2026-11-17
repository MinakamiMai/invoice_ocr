package com.invoiceocr.ocr;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.dto.AdminDtos;
import com.invoiceocr.preprocess.ImagePreprocessor;
import com.invoiceocr.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class OcrEngineService {

    private final Map<String, InvoiceOcrClient> clients = new LinkedHashMap<>();
    private final SystemConfigService configService;
    private final AppProperties appProperties;
    private final ImagePreprocessor imagePreprocessor;

    public OcrEngineService(List<InvoiceOcrClient> clients, SystemConfigService configService,
                            AppProperties appProperties, ImagePreprocessor imagePreprocessor) {
        clients.stream()
                .sorted(Comparator.comparingInt(client -> switch (client.name()) {
                    case "paddle" -> 0;
                    case "baidu" -> 1;
                    case "tesseract" -> 2;
                    case "mock" -> 3;
                    default -> 9;
                }))
                .forEach(client -> this.clients.put(client.name(), client));
        this.configService = configService;
        this.appProperties = appProperties;
        this.imagePreprocessor = imagePreprocessor;
    }

    public String currentEngineName() {
        return configService.get(SystemConfigService.OCR_ENGINE, "paddle");
    }

    public InvoiceOcrClient currentEngine() {
        String name = currentEngineName();
        InvoiceOcrClient client = clients.get(name);
        if (client == null) {
            throw new OcrException("未知 OCR 引擎：" + name);
        }
        return client;
    }

    public OcrRawResult recognize(byte[] bytes, String fileType) {
        String primaryName = currentEngineName();
        InvoiceOcrClient primary = clients.get(primaryName);
        if (primary == null) {
            throw new OcrException("未知 OCR 引擎：" + primaryName);
        }
        try {
            return primary.recognize(prepare(primary, bytes), fileType);
        } catch (RuntimeException primaryError) {
            String fallbackName = configService.get(SystemConfigService.OCR_FALLBACK_ENGINE, "");
            if (!fallbackName.isBlank() && !fallbackName.equals(primaryName) && clients.containsKey(fallbackName)) {
                log.warn("OCR engine {} failed, falling back to {}: {}", primaryName, fallbackName,
                        primaryError.getMessage());
                InvoiceOcrClient fallback = clients.get(fallbackName);
                try {
                    return fallback.recognize(prepare(fallback, bytes), fileType);
                } catch (RuntimeException fallbackError) {
                    throw new OcrException("OCR 引擎 " + primaryName + " 与降级引擎 " + fallbackName
                            + " 均调用失败：" + primaryError.getMessage() + " / " + fallbackError.getMessage(),
                            fallbackError);
                }
            }
            throw primaryError instanceof OcrException ocrException ? ocrException
                    : new OcrException("OCR 引擎 " + primaryName + " 调用失败：" + primaryError.getMessage(),
                    primaryError);
        }
    }

    /**
     * Commercial APIs work best on the original photo; only oversized images are normalized.
     * Local engines need the full grayscale/binarize/deskew pipeline.
     */
    private byte[] prepare(InvoiceOcrClient client, byte[] bytes) {
        try {
            if (client.prefersOriginalImage()) {
                return imagePreprocessor.prepareForApi(bytes);
            }
            if (!configService.getBoolean(SystemConfigService.PREPROCESS_ENABLED, true)) {
                return bytes;
            }
            return imagePreprocessor.process(bytes,
                    configService.getBoolean(SystemConfigService.PREPROCESS_DESKEW, true),
                    configService.getBoolean(SystemConfigService.PREPROCESS_STAMP, true));
        } catch (RuntimeException e) {
            log.warn("Image preparation for engine {} failed, using original image: {}",
                    client.name(), e.getMessage());
            return bytes;
        }
    }

    public List<AdminDtos.EngineInfo> listEngines() {
        return clients.values().stream().map(client -> {
            String detail = switch (client.name()) {
                case "tesseract" -> {
                    String datapath = configService.get("ocr.tesseract.datapath", "");
                    if (datapath.isBlank()) {
                        datapath = appProperties.getOcr().getTesseract().getDatapath();
                    }
                    yield datapath == null || datapath.isBlank() ? "未配置 tessdata 目录"
                            : "tessdata: " + datapath;
                }
                case "baidu" -> client.available() ? "密钥已配置" : "未配置百度 API 密钥";
                case "paddle" -> client.available() ? "Token 已配置" : "未配置 PaddleOCR Token";
                default -> "开发用引擎，不产生文本";
            };
            return new AdminDtos.EngineInfo(client.name(), client.displayName(), client.available(), detail);
        }).toList();
    }
}
