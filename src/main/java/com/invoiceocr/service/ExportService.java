package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.dto.InvoiceQuery;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
public class ExportService {

    public record ExportFile(byte[] content, String contentType, String filename) {
    }

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "invoiceCode", "invoiceNumber", "invoiceDate", "invoiceType",
            "buyerName", "buyerTaxId", "sellerName", "sellerTaxId",
            "totalAmount", "totalTax", "grandTotal", "taxRate", "checkCode",
            "status", "duplicateFlag", "verificationStatus", "ownerUsername", "createdAt");

    private static final Map<String, Column> COLUMNS = new LinkedHashMap<>();

    static {
        column("invoiceCode", "发票代码", item -> item.invoiceCode());
        column("invoiceNumber", "发票号码", item -> item.invoiceNumber());
        column("invoiceDate", "开票日期", item -> item.invoiceDate() == null ? "" : item.invoiceDate().toString());
        column("invoiceType", "发票类型", item -> item.invoiceTypeLabel());
        column("buyerName", "购买方名称", item -> item.buyerName());
        column("buyerTaxId", "购买方税号", item -> item.buyerTaxId());
        column("sellerName", "销售方名称", item -> item.sellerName());
        column("sellerTaxId", "销售方税号", item -> item.sellerTaxId());
        column("totalAmount", "合计金额", item -> money(item.totalAmount()));
        column("totalTax", "合计税额", item -> money(item.totalTax()));
        column("grandTotal", "价税合计", item -> money(item.grandTotal()));
        column("taxRate", "税率", item -> item.taxRate());
        column("checkCode", "校验码", item -> item.checkCode());
        column("status", "状态", item -> item.statusLabel());
        column("duplicateFlag", "重复标记", item -> item.duplicateFlag() ? "是" : "否");
        column("verificationStatus", "验真状态", item -> item.verificationStatusLabel());
        column("ownerUsername", "上传人", item -> item.ownerUsername());
        column("createdAt", "上传时间", item -> item.createdAt() == null ? "" : item.createdAt().toString());
    }

    private static void column(String key, String label, Function<InvoiceDtos.InvoiceListItem, String> extractor) {
        COLUMNS.put(key, new Column(key, label, extractor));
    }

    private final InvoiceQueryService queryService;
    private final InvoiceAssembler assembler;
    private final SystemConfigService configService;
    private final AuditService auditService;

    public ExportFile export(InvoiceQuery query, String format, String columnsCsv) {
        List<String> columns = resolveColumns(columnsCsv);
        int maxRows = configService.getInt(SystemConfigService.EXPORT_MAX_ROWS, 10000);
        List<Invoice> invoices = queryService.listForExport(query, maxRows);
        List<InvoiceDtos.InvoiceListItem> items = invoices.stream()
                .map(assembler::toListItem)
                .toList();
        String stamp = LocalDateTime.now().format(FILE_TIME);
        auditService.log("EXPORT", "INVOICE", null,
                "format=" + format + ", rows=" + items.size() + ", columns=" + columns);
        if ("xlsx".equalsIgnoreCase(format)) {
            return new ExportFile(toExcel(items, columns),
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "invoices-" + stamp + ".xlsx");
        }
        return new ExportFile(toCsv(items, columns), "text/csv;charset=UTF-8",
                "invoices-" + stamp + ".csv");
    }

    private List<String> resolveColumns(String columnsCsv) {
        if (columnsCsv == null || columnsCsv.isBlank()) {
            return new ArrayList<>(DEFAULT_COLUMNS);
        }
        List<String> result = new ArrayList<>();
        for (String raw : columnsCsv.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) {
                continue;
            }
            if (!COLUMNS.containsKey(key)) {
                throw ApiException.badRequest("未知导出字段：" + key);
            }
            result.add(key);
        }
        if (result.isEmpty()) {
            return new ArrayList<>(DEFAULT_COLUMNS);
        }
        return result;
    }

    private byte[] toCsv(List<InvoiceDtos.InvoiceListItem> items, List<String> columns) {
        StringBuilder csv = new StringBuilder();
        csv.append('﻿');
        csv.append(columns.stream().map(key -> escapeCsv(COLUMNS.get(key).label()))
                .reduce((a, b) -> a + "," + b).orElse("")).append("\r\n");
        for (InvoiceDtos.InvoiceListItem item : items) {
            List<String> values = new ArrayList<>();
            for (String key : columns) {
                values.add(escapeCsv(COLUMNS.get(key).extractor().apply(item)));
            }
            csv.append(String.join(",", values)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] toExcel(List<InvoiceDtos.InvoiceListItem> items, List<String> columns) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("发票台账");
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(COLUMNS.get(columns.get(i)).label());
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 20 * 256);
            }
            int rowIndex = 1;
            for (InvoiceDtos.InvoiceListItem item : items) {
                Row row = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    row.createCell(i).setCellValue(safeText(
                            COLUMNS.get(columns.get(i)).extractor().apply(item)));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出 Excel 失败", e);
        }
    }

    private String escapeCsv(String value) {
        String text = safeText(value);
        boolean needsQuote = text.contains(",") || text.contains("\"") || text.contains("\n")
                || text.contains("\r");
        String escaped = text.replace("\"", "\"\"");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    private String safeText(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '=' || first == '+' || first == '@'
                    || (first == '-' && !text.matches("-\\d+(\\.\\d+)?"))) {
                text = "'" + text;
            }
        }
        return text;
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private record Column(String key, String label,
                          Function<InvoiceDtos.InvoiceListItem, String> extractor) {
    }
}
