package com.invoiceocr.service;

import com.invoiceocr.domain.SystemConfigEntity;
import com.invoiceocr.dto.AdminDtos;
import com.invoiceocr.repo.SystemConfigRepository;
import com.invoiceocr.util.CryptoService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SystemConfigService {

    public static final String OCR_ENGINE = "ocr.engine";
    public static final String OCR_FALLBACK_ENGINE = "ocr.fallback-engine";
    public static final String CONFIDENCE_THRESHOLD = "ocr.confidence.threshold";
    public static final String PDF_MIN_TEXT_CHARS = "ocr.pdf.min-text-chars";
    public static final String PDF_MAX_PAGES = "ocr.pdf.max-pages";
    public static final String OFD_MAX_IMAGES = "ocr.ofd.max-images";
    public static final String BAIDU_API_KEY = "ocr.baidu.api-key";
    public static final String BAIDU_SECRET_KEY = "ocr.baidu.secret-key";
    public static final String PREPROCESS_ENABLED = "preprocess.enabled";
    public static final String PREPROCESS_DESKEW = "preprocess.deskew";
    public static final String PREPROCESS_STAMP = "preprocess.stamp-suppression";
    public static final String DEDUP_FALLBACK_ENABLED = "dedup.fallback.enabled";
    public static final String DEDUP_FIELD_HASH_ENABLED = "dedup.field-hash.enabled";
    public static final String VERIFY_TRIGGER = "verify.trigger";
    public static final String VERIFY_CHANNEL = "verify.channel";
    public static final String VERIFY_HTTP_ENDPOINT = "verify.http.endpoint";
    public static final String VERIFY_HTTP_API_KEY = "verify.http.api-key";
    public static final String VERIFY_HTTP_TIMEOUT_MS = "verify.http.timeout-ms";
    public static final String VERIFY_HTTP_RETRIES = "verify.http.retries";
    public static final String STORAGE_RETENTION_YEARS = "storage.retention-years";
    public static final String EXPORT_MAX_ROWS = "export.max-rows";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static final Map<String, String> DESCRIPTIONS = new LinkedHashMap<>();

    static {
        defaults(OCR_ENGINE, "mock", "当前 OCR 引擎：mock / tesseract / baidu");
        defaults(OCR_FALLBACK_ENGINE, "", "商业引擎失败后的降级引擎，可留空");
        defaults(CONFIDENCE_THRESHOLD, "80", "字段置信度阈值（0-100），低于阈值进入复核");
        defaults(PDF_MIN_TEXT_CHARS, "20", "PDF/OFD 文本层少于该字符数时降级为图像 OCR");
        defaults(PDF_MAX_PAGES, "3", "扫描版 PDF 最多 OCR 的页数");
        defaults(OFD_MAX_IMAGES, "3", "扫描版 OFD 最多 OCR 的图片数");
        defaults("ocr.tesseract.datapath", "", "Tesseract tessdata 所在目录（其下应有 chi_sim.traineddata）");
        defaults("ocr.tesseract.language", "chi_sim+eng", "Tesseract 语言包，例如 chi_sim+eng");
        defaults(BAIDU_API_KEY, "", "百度 OCR API Key（加密存储）");
        defaults(BAIDU_SECRET_KEY, "", "百度 OCR Secret Key（加密存储）");
        defaults(PREPROCESS_ENABLED, "true", "是否启用图像预处理");
        defaults(PREPROCESS_DESKEW, "true", "是否启用倾斜校正");
        defaults(PREPROCESS_STAMP, "true", "是否弱化红色印章");
        defaults(DEDUP_FALLBACK_ENABLED, "true", "是否启用降级指纹查重");
        defaults(DEDUP_FIELD_HASH_ENABLED, "true", "是否启用字段哈希碰撞检测");
        defaults(VERIFY_TRIGGER, "MANUAL", "验真触发策略：MANUAL / AUTO / REVIEW_ONLY");
        defaults(VERIFY_CHANNEL, "mock", "验真渠道：mock / http");
        defaults(VERIFY_HTTP_ENDPOINT, "", "HTTP 验真接口地址");
        defaults(VERIFY_HTTP_API_KEY, "", "HTTP 验真接口密钥（加密存储）");
        defaults(VERIFY_HTTP_TIMEOUT_MS, "10000", "验真接口超时毫秒数");
        defaults(VERIFY_HTTP_RETRIES, "2", "验真接口最大重试次数");
        defaults(STORAGE_RETENTION_YEARS, "5", "原始发票文件保留年限");
        defaults(EXPORT_MAX_ROWS, "10000", "单次导出最大行数");
    }

    private static void defaults(String key, String value, String description) {
        DEFAULTS.put(key, value);
        DESCRIPTIONS.put(key, description);
    }

    private final SystemConfigRepository repository;
    private final CryptoService cryptoService;

    @Transactional
    public void seedDefaults() {
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            if (repository.findById(entry.getKey()).isEmpty()) {
                SystemConfigEntity entity = new SystemConfigEntity();
                entity.setConfigKey(entry.getKey());
                entity.setConfigValue(entry.getValue());
                entity.setEncrypted(false);
                entity.setDescription(DESCRIPTIONS.get(entry.getKey()));
                entity.setUpdatedAt(LocalDateTime.now());
                entity.setUpdatedBy("system");
                repository.save(entity);
            }
        }
    }

    public String get(String key) {
        SystemConfigEntity entity = repository.findById(key).orElse(null);
        if (entity == null) {
            return DEFAULTS.get(key);
        }
        if (entity.getConfigValue() == null) {
            return DEFAULTS.get(key);
        }
        return entity.isEncrypted() ? cryptoService.decrypt(entity.getConfigValue()) : entity.getConfigValue();
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    public List<AdminDtos.ConfigItem> listMasked() {
        return repository.findAllByOrderByConfigKeyAsc().stream()
                .map(entity -> {
                    boolean masked = entity.isEncrypted();
                    return new AdminDtos.ConfigItem(entity.getConfigKey(),
                            masked ? "******" : entity.getConfigValue(),
                            entity.isEncrypted(), masked, entity.getDescription(),
                            entity.getUpdatedAt(), entity.getUpdatedBy());
                })
                .toList();
    }

    @Transactional
    public void update(Map<String, String> values, String actor) {
        if (values == null || values.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            if (!DEFAULTS.containsKey(key)) {
                throw new IllegalArgumentException("未知配置项：" + key);
            }
            String value = entry.getValue();
            if (value == null) {
                value = "";
            }
            if (isSensitive(key) && "******".equals(value)) {
                continue;
            }
            validate(key, value);
            SystemConfigEntity entity = repository.findById(key).orElseGet(() -> {
                SystemConfigEntity created = new SystemConfigEntity();
                created.setConfigKey(key);
                created.setDescription(DESCRIPTIONS.get(key));
                return created;
            });
            boolean sensitive = isSensitive(key);
            entity.setEncrypted(sensitive && !value.isBlank());
            entity.setConfigValue(sensitive && !value.isBlank() ? cryptoService.encrypt(value) : value);
            entity.setUpdatedAt(LocalDateTime.now());
            entity.setUpdatedBy(actor);
            repository.save(entity);
        }
    }

    private void validate(String key, String value) {
        switch (key) {
            case OCR_ENGINE -> {
                if (!Set.of("mock", "tesseract", "baidu").contains(value)) {
                    throw new IllegalArgumentException("OCR 引擎只能是 mock / tesseract / baidu");
                }
            }
            case OCR_FALLBACK_ENGINE -> {
                if (!value.isBlank() && !Set.of("mock", "tesseract", "baidu").contains(value)) {
                    throw new IllegalArgumentException("降级 OCR 引擎只能是 mock / tesseract / baidu 或留空");
                }
            }
            case VERIFY_TRIGGER -> {
                if (!Set.of("MANUAL", "AUTO", "REVIEW_ONLY").contains(value)) {
                    throw new IllegalArgumentException("验真触发策略只能是 MANUAL / AUTO / REVIEW_ONLY");
                }
            }
            case VERIFY_CHANNEL -> {
                if (!Set.of("mock", "http").contains(value)) {
                    throw new IllegalArgumentException("验真渠道只能是 mock / http");
                }
            }
            case CONFIDENCE_THRESHOLD -> {
                int threshold = Integer.parseInt(value);
                if (threshold < 0 || threshold > 100) {
                    throw new IllegalArgumentException("置信度阈值应在 0-100 之间");
                }
            }
            case PDF_MIN_TEXT_CHARS, PDF_MAX_PAGES, OFD_MAX_IMAGES, VERIFY_HTTP_TIMEOUT_MS,
                    VERIFY_HTTP_RETRIES, STORAGE_RETENTION_YEARS, EXPORT_MAX_ROWS -> {
                int parsed = Integer.parseInt(value);
                if (parsed < 0 || parsed > 100000) {
                    throw new IllegalArgumentException("数值配置越界：" + key);
                }
            }
            default -> {
            }
        }
    }

    public boolean isSensitive(String key) {
        return key.endsWith(".api-key") || key.endsWith(".secret-key")
                || key.endsWith(".password") || key.endsWith(".token");
    }

    public Set<String> allowedKeys() {
        return DEFAULTS.keySet();
    }
}
