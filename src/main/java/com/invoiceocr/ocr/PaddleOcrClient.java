package com.invoiceocr.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoiceocr.config.AppProperties;
import com.invoiceocr.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PaddleOCR-VL adapter (AIStudio job API). The API is asynchronous: submit a job, poll until it
 * finishes and download the JSONL result. It returns layout blocks whose content may be plain
 * text, Markdown or HTML tables, so the content is normalized into plain lines before it reaches
 * the rule based invoice parser.
 */
@Slf4j
@Component
public class PaddleOcrClient implements InvoiceOcrClient {

    private static final Pattern IMAGE_MARKDOWN = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK_MARKDOWN = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern HTML_BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern HTML_ROW_END = Pattern.compile("(?i)</tr\\s*>");
    private static final Pattern HTML_CELL_END = Pattern.compile("(?i)</t[dh]\\s*>");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^:?-{2,}:?$");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private final SystemConfigService configService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    public PaddleOcrClient(SystemConfigService configService, AppProperties appProperties,
                           ObjectMapper objectMapper, RestTemplateBuilder builder) {
        this.configService = configService;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.restTemplate = builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(120))
                .build();
    }

    @Override
    public String name() {
        return "paddle";
    }

    @Override
    public String displayName() {
        return "PaddleOCR-VL（飞桨星河 API）";
    }

    @Override
    public boolean prefersOriginalImage() {
        return true;
    }

    @Override
    public boolean available() {
        return token() != null && !token().isBlank();
    }

    @Override
    public OcrRawResult recognize(byte[] fileBytes, String fileType) {
        String token = token();
        if (token == null || token.isBlank()) {
            throw new OcrException("未配置 PaddleOCR Token（管理后台 ocr.paddle.token 或环境变量 PADDLEOCR_TOKEN）");
        }
        Upload upload = normalizeForUpload(fileBytes, fileType);
        String jobId = submit(token, upload);
        JsonNode data = poll(token, jobId);
        String jsonUrl = data.path("resultUrl").path("jsonUrl").asText("");
        if (jsonUrl.isBlank()) {
            throw new OcrException("PaddleOCR 任务已完成，但未返回结果下载地址");
        }
        String jsonl = download(jsonUrl);
        return parseJsonl(jsonl);
    }

    private String token() {
        String fileToken = tokenFromFile();
        if (!fileToken.isBlank()) {
            return fileToken;
        }
        String configured = configService.get(SystemConfigService.PADDLE_TOKEN, "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return appProperties.getOcr().getPaddle().getToken() == null
                ? "" : appProperties.getOcr().getPaddle().getToken().trim();
    }

    /**
     * Reads the token from a local, gitignored text file so the repository never contains a secret.
     * The file takes precedence over the encrypted admin setting when present.
     */
    private String tokenFromFile() {
        String configured = configService.get(SystemConfigService.PADDLE_TOKEN_FILE, "");
        String path = configured != null && !configured.isBlank()
                ? configured.trim() : appProperties.getOcr().getPaddle().getTokenFile();
        if (path == null || path.isBlank()) {
            return "";
        }
        try {
            Path file = Path.of(path);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (value.regionMatches(true, 0, "token=", 0, 6)) {
                value = value.substring(6).trim();
            }
            return value;
        } catch (Exception e) {
            log.warn("读取 PaddleOCR Token 文件失败（{}）：{}", path, e.getMessage());
            return "";
        }
    }

    private String model() {
        String configured = configService.get(SystemConfigService.PADDLE_MODEL, "");
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        String fallback = appProperties.getOcr().getPaddle().getModel();
        return fallback == null || fallback.isBlank() ? "PaddleOCR-VL-1.6" : fallback.trim();
    }

    private String baseUrl() {
        String configured = configService.get(SystemConfigService.PADDLE_BASE_URL, "");
        String url = configured != null && !configured.isBlank() ? configured.trim()
                : appProperties.getOcr().getPaddle().getBaseUrl();
        if (url == null || url.isBlank()) {
            url = "https://paddleocr.aistudio-app.com/api/v2/ocr/jobs";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private int intSetting(String key, int fallback) {
        return configService.getInt(key, fallback);
    }

    private String submit(String token, Upload upload) {
        Map<String, Object> optionalPayload = new LinkedHashMap<>();
        optionalPayload.put("useDocOrientationClassify", configService.getBoolean(
                SystemConfigService.PADDLE_ORIENTATION_CLASSIFY,
                appProperties.getOcr().getPaddle().isUseDocOrientationClassify()));
        optionalPayload.put("useDocUnwarping", configService.getBoolean(
                SystemConfigService.PADDLE_DOC_UNWARPING,
                appProperties.getOcr().getPaddle().isUseDocUnwarping()));
        optionalPayload.put("useChartRecognition", configService.getBoolean(
                SystemConfigService.PADDLE_CHART_RECOGNITION,
                appProperties.getOcr().getPaddle().isUseChartRecognition()));

        Exception last = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                ByteArrayResource resource = new ByteArrayResource(upload.bytes()) {
                    @Override
                    public String getFilename() {
                        return upload.filename();
                    }
                };
                HttpHeaders partHeaders = new HttpHeaders();
                partHeaders.setContentType(upload.mediaType());
                body.add("file", new HttpEntity<>(resource, partHeaders));
                body.add("model", model());
                body.add("optionalPayload", objectMapper.writeValueAsString(optionalPayload));

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);
                headers.setBearerAuth(token);

                ResponseEntity<String> response = restTemplate.exchange(baseUrl(), HttpMethod.POST,
                        new HttpEntity<>(body, headers), String.class);
                JsonNode node = objectMapper.readTree(response.getBody() == null ? "{}" : response.getBody());
                int code = node.path("code").asInt(0);
                if (code != 0) {
                    throw new OcrException("PaddleOCR 提交任务失败：" + sanitize(node.path("msg").asText("未知错误")));
                }
                String jobId = node.path("data").path("jobId").asText("");
                if (jobId.isBlank()) {
                    throw new OcrException("PaddleOCR 提交任务成功但未返回 jobId");
                }
                log.info("PaddleOCR job submitted: {}, model={}, file={}", jobId, model(), upload.filename());
                return jobId;
            } catch (OcrException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempt < 2) {
                    sleep(400L * (attempt + 1));
                }
            }
        }
        throw new OcrException("PaddleOCR 提交任务失败：" + sanitize(last == null ? "未知错误" : last.getMessage()), last);
    }

    private JsonNode poll(String token, String jobId) {
        int timeoutSeconds = Math.max(30, intSetting(SystemConfigService.PADDLE_TIMEOUT_SECONDS,
                appProperties.getOcr().getPaddle().getTimeoutSeconds()));
        int intervalSeconds = Math.max(1, intSetting(SystemConfigService.PADDLE_POLL_INTERVAL_SECONDS,
                appProperties.getOcr().getPaddle().getPollIntervalSeconds()));
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        int consecutiveErrors = 0;

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        while (System.nanoTime() < deadline) {
            String raw;
            try {
                ResponseEntity<String> response = restTemplate.exchange(baseUrl() + "/" + jobId,
                        HttpMethod.GET, new HttpEntity<>(headers), String.class);
                raw = response.getBody();
                consecutiveErrors = 0;
            } catch (RestClientException e) {
                consecutiveErrors++;
                if (consecutiveErrors >= 3) {
                    throw new OcrException("PaddleOCR 查询任务状态失败：" + sanitize(e.getMessage()), e);
                }
                log.warn("PaddleOCR poll transient failure ({}): {}", consecutiveErrors, e.getMessage());
                sleep(intervalSeconds * 1000L);
                continue;
            }

            JsonNode data = readTree(raw).path("data");
            String state = data.path("state").asText("").toLowerCase();
            switch (state) {
                case "done" -> {
                    log.info("PaddleOCR job {} finished: {}", jobId, data.path("extractProgress"));
                    return data;
                }
                case "failed" -> throw new OcrException("PaddleOCR 识别失败："
                        + sanitize(data.path("errorMsg").asText("未知错误")));
                case "pending" -> log.debug("PaddleOCR job {} pending", jobId);
                case "running" -> log.debug("PaddleOCR job {} running: {}", jobId, data.path("extractProgress"));
                default -> log.warn("PaddleOCR job {} returned unknown state '{}'", jobId, state);
            }
            sleep(intervalSeconds * 1000L);
        }
        throw new OcrException("PaddleOCR 任务超时（" + timeoutSeconds + " 秒），jobId=" + jobId);
    }

    private String download(String url) {
        try {
            // Pass a pre-built URI: the signed object URL contains %2F/%2B escapes that must not be
            // re-encoded by the URI template handler, otherwise the BOS signature becomes invalid.
            ResponseEntity<byte[]> response = restTemplate.exchange(java.net.URI.create(url),
                    HttpMethod.GET, HttpEntity.EMPTY, byte[].class);
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                throw new OcrException("PaddleOCR 结果文件为空");
            }
            log.debug("PaddleOCR result downloaded: {} bytes, contentType={}",
                    body.length, response.getHeaders().getContentType());
            return new String(body, StandardCharsets.UTF_8);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("下载 PaddleOCR 结果失败：" + sanitize(e.getMessage()), e);
        }
    }

    private OcrRawResult parseJsonl(String jsonl) {
        List<OcrTextBlock> blocks = new ArrayList<>();
        List<String> pageTexts = new ArrayList<>();
        int pageIndex = 0;
        for (String line : jsonl.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode root = readTree(line);
            int errorCode = root.path("errorCode").asInt(0);
            if (errorCode != 0) {
                throw new OcrException("PaddleOCR 结果错误 " + errorCode + "："
                        + sanitize(root.path("errorMsg").asText("未知错误")));
            }
            JsonNode pages = root.path("result").path("layoutParsingResults");
            for (JsonNode page : pages) {
                List<OcrTextBlock> pageBlocks = pageBlocks(page);
                if (!pageBlocks.isEmpty()) {
                    blocks.addAll(pageBlocks);
                    pageTexts.add(joinBlocks(pageBlocks));
                } else {
                    String markdown = normalizeContent(page.path("markdown").path("text").asText(""));
                    if (!markdown.isBlank()) {
                        pageTexts.add(markdown);
                        for (String textLine : markdown.split("\\R")) {
                            if (!textLine.isBlank()) {
                                blocks.add(OcrTextBlock.line(textLine, 95));
                            }
                        }
                    }
                }
                pageIndex++;
            }
        }
        if (blocks.isEmpty()) {
            throw new OcrException("PaddleOCR 未返回任何文本（共解析 " + pageIndex + " 页）");
        }
        return new OcrRawResult("paddle", String.join("\n", pageTexts), blocks, Map.of());
    }

    private List<OcrTextBlock> pageBlocks(JsonNode page) {
        JsonNode parsing = page.path("prunedResult").path("parsing_res_list");
        if (!parsing.isArray() || parsing.isEmpty()) {
            return List.of();
        }
        List<JsonNode> ordered = new ArrayList<>();
        parsing.forEach(ordered::add);
        // Merged table blocks carry block_order=null; sorting would move them to the front, so only
        // sort when every block has a numeric order. Otherwise keep the API's reading order.
        if (ordered.stream().allMatch(node -> node.path("block_order").isNumber())) {
            ordered.sort(Comparator.comparingInt(node -> node.path("block_order").asInt(0)));
        }
        List<OcrTextBlock> blocks = new ArrayList<>();
        for (JsonNode node : ordered) {
            String text = normalizeContent(node.path("block_content").asText(""));
            if (text.isBlank()) {
                continue;
            }
            JsonNode box = node.path("block_bbox");
            int x = box.isArray() && box.size() >= 4 ? box.get(0).asInt() : 0;
            int y = box.isArray() && box.size() >= 4 ? box.get(1).asInt() : 0;
            int width = box.isArray() && box.size() >= 4 ? Math.max(0, box.get(2).asInt() - x) : 0;
            int height = box.isArray() && box.size() >= 4 ? Math.max(0, box.get(3).asInt() - y) : 0;
            blocks.add(new OcrTextBlock(text, 95, x, y, width, height));
        }
        return blocks;
    }

    private String joinBlocks(List<OcrTextBlock> blocks) {
        StringBuilder text = new StringBuilder();
        for (OcrTextBlock block : blocks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(block.text());
        }
        return text.toString();
    }

    /**
     * Flattens Markdown/HTML tables and inline markup into whitespace separated plain lines so the
     * regex based parser can find labels, amounts and tax rates.
     */
    String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String text = content;
        text = IMAGE_MARKDOWN.matcher(text).replaceAll("");
        text = HTML_BREAK.matcher(text).replaceAll("\n");
        text = HTML_ROW_END.matcher(text).replaceAll("\n");
        text = HTML_CELL_END.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll("");
        text = LINK_MARKDOWN.matcher(text).replaceAll("$1");
        text = org.springframework.web.util.HtmlUtils.htmlUnescape(text);
        // The API sometimes returns hard line breaks as literal "\n"/"\t" escapes inside a block.
        text = text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\t", " ");

        StringBuilder result = new StringBuilder();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.replace(' ', ' ').trim();
            if (line.isBlank()) {
                result.append('\n');
                continue;
            }
            if (line.startsWith("```") || line.matches("^[-*_]{3,}$")) {
                continue;
            }
            line = line.replaceFirst("^#{1,6}\\s*", "")
                    .replaceFirst("^>\\s*", "")
                    .replaceFirst("^[-*+]\\s+", "");
            line = line.replace("**", "").replace("__", "").replace("`", "");
            if (line.contains("|")) {
                line = flattenTableRow(line);
                if (line.isBlank()) {
                    continue;
                }
            }
            line = line.replaceAll("[ \\t\\u00A0]+", " ").trim();
            if (!line.isBlank()) {
                result.append(line).append('\n');
            }
        }
        return BLANK_LINES.matcher(result.toString()).replaceAll("\n\n").trim();
    }

    private String flattenTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|")) {
            String value = cell.trim();
            if (value.isEmpty()) {
                continue;
            }
            cells.add(value);
        }
        if (cells.isEmpty() || cells.stream().allMatch(cell -> TABLE_SEPARATOR.matcher(cell).matches())) {
            return "";
        }
        return String.join(" ", cells);
    }

    private JsonNode readTree(String raw) {
        try {
            return objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new OcrException("PaddleOCR 响应解析失败：" + sanitize(e.getMessage()), e);
        }
    }

    private Upload normalizeForUpload(byte[] bytes, String fileType) {
        String format = detectFormat(bytes, fileType);
        if ("bmp".equals(format)) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image != null) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", output);
                    return new Upload(output.toByteArray(), "upload.png", MediaType.IMAGE_PNG);
                }
            } catch (Exception e) {
                log.warn("Failed to convert BMP for PaddleOCR, uploading original: {}", e.getMessage());
            }
        }
        return switch (format) {
            case "pdf" -> new Upload(bytes, "upload.pdf", MediaType.APPLICATION_PDF);
            case "jpg" -> new Upload(bytes, "upload.jpg", MediaType.IMAGE_JPEG);
            default -> new Upload(bytes, "upload.png", MediaType.IMAGE_PNG);
        };
    }

    private String detectFormat(byte[] bytes, String fileType) {
        if (bytes.length >= 4) {
            if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
                return "pdf";
            }
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
                return "jpg";
            }
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
                return "png";
            }
            if (bytes[0] == 'B' && bytes[1] == 'M') {
                return "bmp";
            }
        }
        String type = fileType == null ? "" : fileType.toLowerCase();
        return switch (type) {
            case "pdf" -> "pdf";
            case "jpg", "jpeg" -> "jpg";
            case "bmp" -> "bmp";
            default -> "png";
        };
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("PaddleOCR 调用被中断", e);
        }
    }

    private String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return message
                .replaceAll("(?i)(bearer\\s+)[^\\s&,]+", "$1***")
                .replaceAll("(?i)(access_token|client_secret|token|api-key|apikey)=[^&\\s,]*", "$1=***");
    }

    private record Upload(byte[] bytes, String filename, MediaType mediaType) {
    }
}
