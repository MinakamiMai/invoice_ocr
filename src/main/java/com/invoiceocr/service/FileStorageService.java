package com.invoiceocr.service;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class FileStorageService {

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "ofd", "application/ofd",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "bmp", "image/bmp"
    );

    private final Path root;
    private final Path filesRoot;
    private final Path ocrRoot;

    public FileStorageService(AppProperties properties) {
        this.root = Path.of(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        this.filesRoot = root.resolve("files");
        this.ocrRoot = root.resolve("ocr-results");
        try {
            Files.createDirectories(filesRoot);
            Files.createDirectories(ocrRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create storage directories under " + root, e);
        }
    }

    public String storeInvoiceFile(String invoiceId, String extension, byte[] bytes) {
        String normalizedExt = sanitizeExtension(extension);
        try {
            Path directory = filesRoot.resolve(LocalDate.now().format(DATE_PATH));
            Files.createDirectories(directory);
            String filename = invoiceId + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + normalizedExt;
            Path target = directory.resolve(filename).normalize();
            if (!target.startsWith(filesRoot)) {
                throw new IllegalStateException("Invalid storage path");
            }
            Files.write(target, bytes);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR",
                    "文件保存失败：" + e.getMessage());
        }
    }

    public String storeOcrResult(String invoiceId, long taskId, byte[] json) {
        try {
            Path directory = ocrRoot.resolve(invoiceId);
            Files.createDirectories(directory);
            Path target = directory.resolve(taskId + ".json").normalize();
            if (!target.startsWith(ocrRoot)) {
                throw new IllegalStateException("Invalid OCR result path");
            }
            Files.write(target, json);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR",
                    "OCR 结果保存失败：" + e.getMessage());
        }
    }

    public byte[] read(String ref) {
        try {
            return Files.readAllBytes(resolve(ref));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在或无法读取");
        }
    }

    public Path resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件引用为空");
        }
        Path resolved = root.resolve(ref).normalize();
        if (!resolved.startsWith(root)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_PATH", "非法文件路径");
        }
        return resolved;
    }

    public String contentType(String extension) {
        if (extension == null) {
            return "application/octet-stream";
        }
        return CONTENT_TYPES.getOrDefault(extension.toLowerCase(Locale.ROOT).replace(".", ""),
                "application/octet-stream");
    }

    public long size(String ref) {
        try {
            return Files.size(resolve(ref));
        } catch (IOException e) {
            return 0;
        }
    }

    private String sanitizeExtension(String extension) {
        if (extension == null) {
            throw ApiException.unsupportedMedia("缺少文件扩展名");
        }
        String ext = extension.toLowerCase(Locale.ROOT).replace(".", "").trim();
        if (!ext.matches("[a-z0-9]{2,8}")) {
            throw ApiException.unsupportedMedia("非法文件扩展名");
        }
        return ext;
    }
}
