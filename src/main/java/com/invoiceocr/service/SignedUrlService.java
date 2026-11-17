package com.invoiceocr.service;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.util.HashUtil;
import com.invoiceocr.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SignedUrlService {

    private final String secret;
    private final long ttlSeconds;

    public SignedUrlService(AppProperties properties) {
        this.secret = properties.getSecurity().getJwt().getSecret() + "|invoice-preview";
        this.ttlSeconds = Math.max(30, properties.getStorage().getPreviewTtlSeconds());
    }

    public String previewUrl(String invoiceId) {
        long expires = Instant.now().getEpochSecond() + ttlSeconds;
        String signature = sign(invoiceId, expires);
        return "/api/invoices/" + invoiceId + "/file?expires=" + expires + "&sig=" + signature;
    }

    public void verify(String invoiceId, Long expires, String signature) {
        if (expires == null || signature == null || signature.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_SIGNATURE", "预览链接缺少签名");
        }
        if (expires < Instant.now().getEpochSecond()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EXPIRED_SIGNATURE", "预览链接已过期");
        }
        if (expires > Instant.now().getEpochSecond() + ttlSeconds + 300) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_SIGNATURE", "预览链接有效期非法");
        }
        String expected = sign(invoiceId, expires);
        if (!HashUtil.constantTimeEquals(expected, signature)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_SIGNATURE", "预览链接签名校验失败");
        }
    }

    private String sign(String invoiceId, long expires) {
        return HashUtil.hmacSha256Hex(secret, invoiceId + ":" + expires);
    }
}
