package com.invoiceocr;

import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.ocr.OcrRawResult;
import com.invoiceocr.ocr.PdfDocumentExtractor;
import com.invoiceocr.parser.InvoiceParser;
import com.invoiceocr.parser.ParsedInvoice;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generates samples/sample-invoice.pdf (text layer, embeddable Chinese font) and proves that
 * the PDF text path plus parser can round-trip a real invoice file without running an OCR engine.
 */
class SampleInvoiceGeneratorTest {

    private static final String[] LINES = {
            "增值税电子普通发票",
            "发票代码: 011002200111",
            "发票号码: 12345678",
            "开票日期: 2026年11月10日",
            "校验码: 12345678901234567890",
            "机器编号: 661234567890",
            "购买方 名称: 北京示例科技有限公司 纳税人识别号: 91110108MA01ABCD2X",
            "地址、电话: 北京市海淀区示例路1号 010-88886666",
            "开户行及账号: 中国工商银行北京分行 0200001234567890123",
            "货物或应税劳务、服务名称 规格型号 单位 数量 单价 金额 税率 税额",
            "技术服务费 项 1 1000.00 1000.00 6% 60.00",
            "合计 ￥1000.00 ￥60.00",
            "价税合计(大写) 壹仟零陆拾元整 (小写) ￥1060.00",
            "销售方 名称: 上海示例服务有限公司 纳税人识别号: 91310115MA1KXYZ12A",
            "地址、电话: 上海市浦东新区示例路2号 021-66668888",
            "开户行及账号: 中国建设银行上海分行 3100001234567890123",
            "收款人: 张三 复核人: 李四 开票人: 王五"
    };

    @Test
    void generatesSamplePdfAndParsesIt() throws Exception {
        Path font = Path.of("C:/Windows/Fonts/simhei.ttf");
        Assumptions.assumeTrue(Files.exists(font), "SimHei font is not available on this machine");
        byte[] pdf = generatePdf(font);
        Path output = Path.of("samples", "sample-invoice.pdf");
        Files.createDirectories(output.getParent());
        Files.write(output, pdf);

        assertTrue(pdf.length > 1000);
        String extracted = new PdfDocumentExtractor().extractText(pdf);
        assertTrue(extracted.contains("12345678"));
        assertTrue(extracted.contains("上海示例服务有限公司"));

        ParsedInvoice invoice = new InvoiceParser().parse(OcrRawResult.text("pdf-text", extracted), null);
        assertEquals(InvoiceType.VAT_E_NORMAL, invoice.getInvoiceType());
        assertEquals("12345678", invoice.getInvoiceNumber());
        assertEquals("上海示例服务有限公司", invoice.getSellerName());
        assertEquals(0, new java.math.BigDecimal("1060.00").compareTo(invoice.getGrandTotal()));
        assertTrue(invoice.getWarnings().isEmpty(), () -> "Unexpected warnings: " + invoice.getWarnings());
    }

    private byte[] generatePdf(Path fontFile) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font font = PDType0Font.load(document, fontFile.toFile());
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 10.5f);
                content.setLeading(21f);
                content.newLineAtOffset(50, 780);
                for (String line : LINES) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
