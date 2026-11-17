package com.invoiceocr.ocr;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal OFD reader: OFD is a ZIP package; digital OFD stores text in Content.xml TextCode elements.
 * For scanned OFD files, embedded images are returned and handed to the OCR engine.
 */
@Slf4j
@Component
public class OfdDocumentExtractor {

    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;

    public record OfdContent(String text, List<byte[]> images) {
    }

    public OfdContent extract(byte[] ofdBytes) {
        Map<String, byte[]> xmlEntries = new LinkedHashMap<>();
        List<byte[]> images = new ArrayList<>();
        long total = 0;
        int entryCount = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(ofdBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new OcrException("OFD 压缩包条目过多");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] content = readLimited(zip, MAX_ENTRY_BYTES);
                total += content.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new OcrException("OFD 解压后体积超过限制");
                }
                String name = entry.getName();
                if (name.endsWith(".xml")) {
                    xmlEntries.put(name, content);
                } else if (isImage(content)) {
                    images.add(content);
                }
            }
        } catch (IOException e) {
            throw new OcrException("OFD 解析失败：" + e.getMessage(), e);
        }

        xmlEntries.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> log.debug("OFD xml: {}", entry.getKey()));

        StringBuilder text = new StringBuilder();
        xmlEntries.entrySet().stream()
                .filter(e -> e.getKey().contains("Content.xml") || e.getKey().contains("Content"))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> text.append(extractTextFromXml(e.getValue())).append('\n'));

        return new OfdContent(text.toString().trim(), images);
    }

    private String extractTextFromXml(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml));
            NodeList nodes = document.getElementsByTagName("*");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (!(node instanceof Element element)) {
                    continue;
                }
                String localName = element.getLocalName() == null ? element.getNodeName() : element.getLocalName();
                if ("TextCode".equals(localName) || "Text".equals(localName)) {
                    String value = element.getTextContent();
                    if (value != null && !value.isBlank()) {
                        result.append(value.trim()).append('\n');
                    }
                }
            }
            return result.toString();
        } catch (Exception e) {
            log.debug("Failed to parse OFD XML: {}", e.getMessage());
            return "";
        }
    }

    private byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        int n;
        while ((n = input.read(buffer)) > 0) {
            read += n;
            if (read > limit) {
                throw new OcrException("OFD 单个条目超过大小限制");
            }
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    private boolean isImage(byte[] content) {
        if (content.length < 8) {
            return false;
        }
        int b0 = content[0] & 0xFF;
        int b1 = content[1] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8) {
            return true;
        }
        if (b0 == 0x89 && b1 == 0x50 && content[2] == 0x4E && content[3] == 0x47) {
            return true;
        }
        return b0 == 0x42 && b1 == 0x4D;
    }

    public boolean isOfd(byte[] content) {
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') {
            return false;
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null && count++ < 50) {
                String name = entry.getName();
                if ("OFD.xml".equals(name) || name.endsWith("/OFD.xml")) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
