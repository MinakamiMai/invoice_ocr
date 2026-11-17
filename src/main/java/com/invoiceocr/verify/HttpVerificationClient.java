package com.invoiceocr.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.domain.VerificationStatus;
import com.invoiceocr.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic HTTP verification adapter. The endpoint receives the six elements as JSON and should
 * return { "status": "CONSISTENT|INCONSISTENT|NOT_FOUND", "message": "..." }.
 * Keys are never logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpVerificationClient implements InvoiceVerificationClient {

    private final SystemConfigService configService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "http";
    }

    @Override
    public VerificationResult verify(InvoiceVerifyRequest request) {
        String endpoint = configService.get(SystemConfigService.VERIFY_HTTP_ENDPOINT, "");
        if (endpoint == null || endpoint.isBlank()) {
            return new VerificationResult(VerificationStatus.INTERFACE_ERROR, "http", "未配置验真接口地址");
        }
        String apiKey = configService.get(SystemConfigService.VERIFY_HTTP_API_KEY, "");
        int retries = Math.max(0, Math.min(2, configService.getInt(SystemConfigService.VERIFY_HTTP_RETRIES, 2)));
        int timeoutMs = configService.getInt(SystemConfigService.VERIFY_HTTP_TIMEOUT_MS, 10000);
        RestTemplate client = restTemplate;
        if (timeoutMs > 0) {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(timeoutMs);
            factory.setReadTimeout(timeoutMs);
            client = new RestTemplate(factory);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("invoiceCode", request.invoiceCode());
        payload.put("invoiceNumber", request.invoiceNumber());
        payload.put("invoiceDate", request.invoiceDate() == null ? null : request.invoiceDate().toString());
        payload.put("checkCode", request.checkCode());
        payload.put("amountWithoutTax", request.amountWithoutTax());
        payload.put("invoiceType", request.invoiceType());
        payload.put("grandTotal", request.grandTotal());

        Exception last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                if (apiKey != null && !apiKey.isBlank()) {
                    headers.setBearerAuth(apiKey);
                }
                String raw = client.postForObject(endpoint,
                        new HttpEntity<>(payload, headers), String.class);
                if (raw == null || raw.isBlank()) {
                    return new VerificationResult(VerificationStatus.INTERFACE_ERROR, "http", "验真接口返回空响应");
                }
                JsonNode node = objectMapper.readTree(raw);
                String statusValue = node.path("status").asText(node.path("result").asText(""));
                String message = node.path("message").asText(node.path("summary").asText(raw));
                return new VerificationResult(VerificationStatus.fromExternal(statusValue), "http",
                        truncate(message));
            } catch (Exception e) {
                last = e;
                if (attempt < retries) {
                    sleep(300L * (attempt + 1));
                }
            }
        }
        return new VerificationResult(VerificationStatus.INTERFACE_ERROR, "http",
                "验真接口调用失败：" + (last == null ? "未知错误" : last.getMessage()));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
