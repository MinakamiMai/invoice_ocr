package com.invoiceocr.service;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.ocr.OfdDocumentExtractor;
import com.invoiceocr.util.HashUtil;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class FileValidationService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "pdf", "ofd");
    private static final Map<String, String> EXTENSION_MIME = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "bmp", "image/bmp",
            "pdf", "application/pdf",
            "ofd", "application/ofd"
    );

    private final AppProperties properties;
    private final OfdDocumentExtractor ofdDocumentExtractor;

    public record ValidatedFile(byte[] bytes, String extension, String contentType,
                                String fileHash, String originalFilename, long size) {
    }

    public ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("请选择要上传的发票文件");
        }
        if (file.getSize() > properties.getStorage().getMaxFileSize()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "文件超过大小限制（" + properties.getStorage().getMaxFileSize() + " 字节）");
        }
        String original = sanitizeFilename(file.getOriginalFilename());
        int dot = original.lastIndexOf('.');
        if (dot < 0 || dot == original.length() - 1) {
            throw ApiException.unsupportedMedia("文件缺少扩展名");
        }
        String extension = original.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw ApiException.unsupportedMedia("仅支持 JPG、PNG、BMP、PDF、OFD 格式");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("读取上传文件失败");
        }
        if (bytes.length == 0) {
            throw ApiException.badRequest("上传文件为空");
        }
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank()
                && !declared.equalsIgnoreCase("application/octet-stream")
                && !declared.equalsIgnoreCase(EXTENSION_MIME.get(extension))) {
            boolean ofdZip = "ofd".equals(extension) && (declared.equalsIgnoreCase("application/zip")
                    || declared.equalsIgnoreCase("application/x-zip-compressed"));
            boolean jpegAlias = ("jpg".equals(extension) || "jpeg".equals(extension))
                    && declared.equalsIgnoreCase("image/jpg");
            if (!ofdZip && !jpegAlias) {
                throw ApiException.unsupportedMedia("文件类型与扩展名不一致");
            }
        }
        if (!matchesMagic(bytes, extension)) {
            throw ApiException.unsupportedMedia("文件内容与扩展名不一致，已拒绝伪装文件");
        }
        return new ValidatedFile(bytes, extension, EXTENSION_MIME.get(extension),
                HashUtil.sha256Hex(bytes), original, bytes.length);
    }

    private boolean matchesMagic(byte[] bytes, String extension) {
        if (bytes.length < 4) {
            return false;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        int b3 = bytes[3] & 0xFF;
        return switch (extension) {
            case "jpg", "jpeg" -> b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
            case "png" -> b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47;
            case "bmp" -> b0 == 0x42 && b1 == 0x4D;
            case "pdf" -> b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F';
            case "ofd" -> ofdDocumentExtractor.isOfd(bytes);
            default -> false;
        };
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "invoice";
        }
        String normalized = filename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = normalized.replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
        if (normalized.isBlank()) {
            return "invoice";
        }
        return normalized.length() > 255 ? normalized.substring(normalized.length() - 255) : normalized;
    }
}
