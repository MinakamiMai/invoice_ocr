package com.invoiceocr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Storage storage = new Storage();
    private Security security = new Security();
    private Ocr ocr = new Ocr();
    private Seed seed = new Seed();

    @Data
    public static class Storage {
        private String root = "./data/storage";
        private long maxFileSize = 10L * 1024 * 1024;
        private long previewTtlSeconds = 600;
    }

    @Data
    public static class Security {
        private Jwt jwt = new Jwt();
        private String encryptionKey = "";
    }

    @Data
    public static class Jwt {
        private String secret = "invoice-ocr-dev-secret-key-change-me-in-production-0123456789";
        private long ttlMinutes = 480;
    }

    @Data
    public static class Ocr {
        private Tesseract tesseract = new Tesseract();
        private Async async = new Async();
    }

    @Data
    public static class Tesseract {
        private String datapath = "";
        private String language = "chi_sim+eng";
    }

    @Data
    public static class Async {
        private int corePoolSize = 4;
        private int maxPoolSize = 8;
        private int queueCapacity = 100;
    }

    @Data
    public static class Seed {
        private boolean enabled = true;
        private String adminPassword = "Admin@123";
        private String reviewerPassword = "Review@123";
        private String submitterPassword = "Submit@123";
        private String auditorPassword = "Audit@123";
    }
}
