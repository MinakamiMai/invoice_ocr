package com.invoiceocr.parser;

import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.ocr.OcrRawResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceParserTest {

    static final String SAMPLE_TEXT = """
            增值税电子普通发票
            发票代码: 011002200111
            发票号码: 12345678
            开票日期: 2026年11月10日
            校验码: 12345678901234567890
            机器编号: 661234567890
            购买方 名称: 北京示例科技有限公司 纳税人识别号: 91110108MA01ABCD2X
            地址、电话: 北京市海淀区示例路1号 010-88886666
            开户行及账号: 中国工商银行北京分行 0200001234567890123
            货物或应税劳务、服务名称 规格型号 单位 数量 单价 金额 税率 税额
            技术服务费 项 1 1000.00 1000.00 6% 60.00
            合计 ￥1000.00 ￥60.00
            价税合计(大写) 壹仟零陆拾元整 (小写) ￥1060.00
            销售方 名称: 上海示例服务有限公司 纳税人识别号: 91310115MA1KXYZ12A
            地址、电话: 上海市浦东新区示例路2号 021-66668888
            开户行及账号: 中国建设银行上海分行 3100001234567890123
            收款人: 张三 复核人: 李四 开票人: 王五
            """;

    private final InvoiceParser parser = new InvoiceParser();

    @Test
    void parsesStandardElectronicInvoice() {
        ParsedInvoice invoice = parser.parse(OcrRawResult.text("test", SAMPLE_TEXT), null);

        assertEquals(InvoiceType.VAT_E_NORMAL, invoice.getInvoiceType());
        assertEquals("011002200111", invoice.getInvoiceCode());
        assertEquals("12345678", invoice.getInvoiceNumber());
        assertEquals(LocalDate.of(2026, 11, 10), invoice.getInvoiceDate());
        assertEquals("12345678901234567890", invoice.getCheckCode());
        assertEquals("661234567890", invoice.getMachineNumber());

        assertEquals("北京示例科技有限公司", invoice.getBuyerName());
        assertEquals("91110108MA01ABCD2X", invoice.getBuyerTaxId());
        assertEquals("上海示例服务有限公司", invoice.getSellerName());
        assertEquals("91310115MA1KXYZ12A", invoice.getSellerTaxId());
        assertEquals("北京市海淀区示例路1号 010-88886666", invoice.getBuyerAddressPhone());
        assertEquals("中国工商银行北京分行 0200001234567890123", invoice.getBuyerBankAccount());

        assertMoney("1000.00", invoice.getTotalAmount());
        assertMoney("60.00", invoice.getTotalTax());
        assertMoney("1060.00", invoice.getGrandTotal());
        assertEquals("壹仟零陆拾元整", invoice.getGrandTotalCn());
        assertEquals("6%", invoice.getTaxRate());
        assertEquals("张三", invoice.getPayee());
        assertEquals("李四", invoice.getReviewerName());
        assertEquals("王五", invoice.getDrawer());

        assertEquals(1, invoice.getItems().size());
        ParsedItem item = invoice.getItems().get(0);
        assertTrue(item.getItemName().contains("技术服务费"));
        assertMoney("1000.00", item.getAmount());
        assertMoney("60.00", item.getTaxAmount());
        assertEquals("6%", item.getTaxRate());

        assertEquals(100, invoice.confidenceOf(FieldNames.INVOICE_NUMBER));
        assertNotNull(invoice.confidenceOf(FieldNames.SELLER_TAX_ID));
        assertTrue(invoice.getWarnings().isEmpty(), () -> "Unexpected warnings: " + invoice.getWarnings());
    }

    @Test
    void detectsDigitalInvoiceByTwentyDigitNumberWithoutCode() {
        String text = """
                电子发票(增值税专用发票)
                发票号码: 24312000000012345678
                开票日期: 2026-11-11
                购买方 名称: 北京示例科技有限公司 纳税人识别号: 91110108MA01ABCD2X
                销售方 名称: 上海示例服务有限公司 纳税人识别号: 91310115MA1KXYZ12A
                合计 ￥100.00 ￥6.00
                价税合计(大写) 壹佰零陆元整 (小写) ￥106.00
                """;
        ParsedInvoice invoice = parser.parse(OcrRawResult.text("test", text), null);
        assertEquals(InvoiceType.DIGITAL, invoice.getInvoiceType());
    }

    @Test
    void keepsLowConfidenceWhenBlocksAreUncertain() {
        OcrRawResult raw = new OcrRawResult("test", SAMPLE_TEXT,
                java.util.List.of(new com.invoiceocr.ocr.OcrTextBlock(SAMPLE_TEXT, 55, 0, 0, 0, 0)),
                java.util.Map.of());
        ParsedInvoice invoice = parser.parse(raw, null);
        assertEquals(55, invoice.confidenceOf(FieldNames.INVOICE_NUMBER));
    }

    @Test
    void marksMalformedTaxIdFromCommercialEngineHints() {
        OcrRawResult raw = new OcrRawResult("test", SAMPLE_TEXT,
                java.util.List.of(new com.invoiceocr.ocr.OcrTextBlock(SAMPLE_TEXT, 100, 0, 0, 0, 0)),
                java.util.Map.of("SellerRegisterNum",
                        new com.invoiceocr.ocr.OcrFieldHint("BAD-TAX-ID", 95)));
        ParsedInvoice invoice = parser.parse(raw, null);
        assertFalse(invoice.getWarnings().isEmpty());
        assertTrue(invoice.getWarnings().stream().anyMatch(w -> w.contains("销售方")));
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
