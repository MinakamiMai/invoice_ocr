package com.invoiceocr.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Baidu VAT invoice OCR adapter (vat_invoice API). Credentials are stored encrypted in system_config.
 * Retries twice as required by the non-functional spec.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaiduOcrClient implements InvoiceOcrClient {

    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String INVOICE_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/vat_invoice";

    private final SystemConfigService configService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;
    private volatile String tokenKeyFingerprint;

    @Override
    public String name() {
        return "baidu";
    }

    @Override
    public String displayName() {
        return "百度增值税发票识别 API";
    }

    @Override
    public OcrRawResult recognize(byte[] fileBytes, String fileType) {
        String apiKey = configService.get(SystemConfigService.BAIDU_API_KEY, "");
        String secretKey = configService.get(SystemConfigService.BAIDU_SECRET_KEY, "");
        if (apiKey.isBlank() || secretKey.isBlank()) {
            throw new OcrException("未配置百度 OCR 的 api-key / secret-key");
        }
        String token = accessToken(apiKey, secretKey);
        JsonNode response = callWithRetry(token, fileBytes);
        JsonNode errorCode = response.get("error_code");
        if (errorCode != null && !errorCode.isNull()) {
            throw new OcrException("百度 OCR 返回错误 " + errorCode.asText() + "："
                    + response.path("error_msg").asText());
        }
        JsonNode wordsResult = response.path("words_result");
        Map<String, OcrFieldHint> hints = new LinkedHashMap<>();
        if (wordsResult.isObject()) {
            wordsResult.fields().forEachRemaining(entry -> {
                String value = extractWord(entry.getValue());
                if (value != null && !value.isBlank()) {
                    hints.put(entry.getKey(), new OcrFieldHint(value, 95));
                }
            });
        }
        String fullText = buildFullText(hints, wordsResult);
        return new OcrRawResult("baidu", fullText, List.of(), hints);
    }

    @Override
    public boolean prefersOriginalImage() {
        return true;
    }

    @Override
    public boolean available() {
        return !configService.get(SystemConfigService.BAIDU_API_KEY, "").isBlank()
                && !configService.get(SystemConfigService.BAIDU_SECRET_KEY, "").isBlank();
    }

    private JsonNode callWithRetry(String token, byte[] fileBytes) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
                body.add("image", Base64.getEncoder().encodeToString(fileBytes));
                body.add("detect_direction", "true");
                HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(body, headers);
                String raw = restTemplate.postForObject(INVOICE_URL + "?access_token=" + token,
                        entity, String.class);
                if (raw == null) {
                    throw new OcrException("百度 OCR 返回空响应");
                }
                return objectMapper.readTree(raw);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < 2) {
                    try {
                        Thread.sleep(300L * (attempt + 1));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                throw new OcrException("百度 OCR 响应解析失败", e);
            }
        }
        throw new OcrException("百度 OCR 调用失败：" + (last == null ? "未知错误" : sanitize(last.getMessage())), last);
    }

    private synchronized String accessToken(String apiKey, String secretKey) {
        String fingerprint = Integer.toHexString((apiKey + "|" + secretKey).hashCode());
        if (cachedToken != null && Instant.now().isBefore(tokenExpiresAt)
                && fingerprint.equals(tokenKeyFingerprint)) {
            return cachedToken;
        }
        String url = TOKEN_URL + "?grant_type=client_credentials&client_id="
                + java.net.URLEncoder.encode(apiKey, java.nio.charset.StandardCharsets.UTF_8)
                + "&client_secret=" + java.net.URLEncoder.encode(secretKey, java.nio.charset.StandardCharsets.UTF_8);
        try {
            String raw = restTemplate.getForObject(url, String.class);
            JsonNode node = objectMapper.readTree(raw == null ? "{}" : raw);
            String token = node.path("access_token").asText("");
            if (token.isBlank()) {
                throw new OcrException("百度 OCR 获取 access_token 失败："
                        + sanitize(node.path("error_description").asText()));
            }
            long expiresIn = node.path("expires_in").asLong(2592000);
            cachedToken = token;
            tokenKeyFingerprint = fingerprint;
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(60, expiresIn - 120));
            return token;
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("百度 OCR 获取 access_token 失败：" + sanitize(e.getMessage()), e);
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message.replaceAll("(access_token|client_id|client_secret)=[^&\\s]+", "$1=***");
    }

    private String extractWord(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = extractWord(item);
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            });
            return String.join(",", values);
        }
        if (node.has("word")) {
            return node.path("word").asText(null);
        }
        return node.asText(null);
    }

    private String buildFullText(Map<String, OcrFieldHint> hints, JsonNode wordsResult) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("InvoiceCode", "发票代码");
        labels.put("InvoiceNum", "发票号码");
        labels.put("InvoiceDate", "开票日期");
        labels.put("PurchaserName", "购买方 名称");
        labels.put("PurchaserRegisterNum", "购买方 纳税人识别号");
        labels.put("PurchaserAddress", "购买方 地址、电话");
        labels.put("PurchaserBank", "购买方 开户行及账号");
        labels.put("SellerName", "销售方 名称");
        labels.put("SellerRegisterNum", "销售方 纳税人识别号");
        labels.put("SellerAddress", "销售方 地址、电话");
        labels.put("SellerBank", "销售方 开户行及账号");
        labels.put("TotalAmount", "合计金额");
        labels.put("TotalTax", "合计税额");
        labels.put("AmountInWords", "价税合计(大写)");
        labels.put("AmountInFiguers", "价税合计(小写) ￥");
        labels.put("CheckCode", "校验码");
        labels.put("Payee", "收款人");
        labels.put("Checker", "复核人");
        labels.put("Reviewer", "复核人");
        labels.put("NoteIssuer", "开票人");
        StringBuilder text = new StringBuilder();
        labels.forEach((key, label) -> {
            OcrFieldHint hint = hints.get(key);
            if (hint != null) {
                text.append(label).append(": ").append(hint.value()).append('\n');
            }
        });
        appendCommodityLines(text, wordsResult);
        return text.toString();
    }

    private void appendCommodityLines(StringBuilder text, JsonNode wordsResult) {
        JsonNode names = wordsResult.path("CommodityName");
        if (names.isMissingNode() || names.isNull()) {
            return;
        }
        List<String> nameList = toList(names);
        List<String> unitList = toList(wordsResult.path("CommodityUnit"));
        List<String> numList = toList(wordsResult.path("CommodityNum"));
        List<String> priceList = toList(wordsResult.path("CommodityPrice"));
        List<String> amountList = toList(wordsResult.path("CommodityAmount"));
        List<String> rateList = toList(wordsResult.path("CommodityTaxRate"));
        List<String> taxList = toList(wordsResult.path("CommodityTax"));
        if (nameList.isEmpty()) {
            return;
        }
        // The rule parser locates the item table by this header line.
        text.append("货物或应税劳务、服务名称 规格型号 单位 数量 单价 金额 税率 税额\n");
        for (int i = 0; i < nameList.size(); i++) {
            String rate = at(rateList, i);
            if (rate != null && !rate.contains("%")) {
                try {
                    java.math.BigDecimal value = new java.math.BigDecimal(rate);
                    if (value.compareTo(java.math.BigDecimal.ONE) <= 0) {
                        rate = value.multiply(java.math.BigDecimal.valueOf(100))
                                .stripTrailingZeros().toPlainString() + "%";
                    }
                } catch (NumberFormatException ignored) {
                    // keep original
                }
            }
            text.append(nameList.get(i)).append(' ')
                    .append(at(unitList, i) == null ? "" : at(unitList, i)).append(' ')
                    .append(at(numList, i) == null ? "" : at(numList, i)).append(' ')
                    .append(at(priceList, i) == null ? "" : at(priceList, i)).append(' ')
                    .append(at(amountList, i) == null ? "" : at(amountList, i)).append(' ')
                    .append(rate == null ? "" : rate).append(' ')
                    .append(at(taxList, i) == null ? "" : at(taxList, i)).append('\n');
        }
    }

    private List<String> toList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(extractWord(item)));
        } else {
            String value = extractWord(node);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private String at(List<String> list, int index) {
        return index < list.size() ? list.get(index) : null;
    }
}
