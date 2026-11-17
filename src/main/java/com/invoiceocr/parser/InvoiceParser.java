package com.invoiceocr.parser;

import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.ocr.OcrFieldHint;
import com.invoiceocr.ocr.OcrRawResult;
import com.invoiceocr.ocr.OcrTextBlock;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based invoice field parser. All regexes live here (or in this class's pattern constants)
 * so that parsing rules do not scatter across the code base.
 */
@Component
public class InvoiceParser {

    private static final Pattern INVOICE_CODE = Pattern.compile("发票代码\\s*[:：]?\\s*(\\d{10,12})");
    private static final Pattern INVOICE_NUMBER = Pattern.compile("发票号码\\s*[:：]?\\s*(\\d{8,20})");
    private static final Pattern INVOICE_DATE = Pattern.compile(
            "开票日期\\s*[:：]?\\s*(\\d{4}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日|\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}|\\d{8})");
    private static final Pattern CHECK_CODE = Pattern.compile("校验码\\s*[:：]?\\s*([0-9 ]{6,30})");
    private static final Pattern MACHINE_NUMBER = Pattern.compile("机器编号\\s*[:：]?\\s*([0-9]{8,20})");
    private static final Pattern TAX_RATE = Pattern.compile("(?:税率|征收率)\\s*[:：]?\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*%");
    private static final Pattern ANY_TAX_RATE = Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*%");

    private static final Pattern NAME = Pattern.compile(
            "名\\s*称\\s*[:：]\\s*([^\\n]{2,200}?)(?=\\s*(?:纳税人识别号|统一社会信用代码|税号|地址|开户行|销售方|购买方|$))");
    private static final Pattern TAX_ID = Pattern.compile(
            "(?:纳税人识别号|统一社会信用代码|税号)\\s*[:：]?\\s*([0-9A-Za-z]{15,20})");
    private static final Pattern ADDRESS_PHONE = Pattern.compile(
            "(?:地址\\s*[、,，]?\\s*电话|地址电话|地址\\s*及\\s*电话)\\s*[:：]?\\s*([^\\n]{3,200}?)(?=\\s*(?:开户行|账号|帐号|销售方|购买方|$))");
    private static final Pattern BANK_ACCOUNT = Pattern.compile(
            "(?:开户行及账号|开户行及帐号|开户行账号|开户行及帐号|开户行)\\s*[:：]?\\s*([^\\n]{3,200})");

    // OCR engines may render the currency mark as ¥, ￥, Y or an unknown glyph; allow a few
    // non-digit characters between "合计" and the two amounts.
    private static final Pattern TOTAL_PAIR = Pattern.compile(
            "合\\s*计\\s*[:：]?[^0-9\\n]{0,6}([\\d,]+\\.\\d{1,2})[^0-9\\n]{0,6}([\\d,]+\\.\\d{1,2})");
    private static final Pattern GRAND_TOTAL = Pattern.compile(
            "(?:\\(?小写\\)?|小写)\\s*[:：]?\\s*[￥¥]?\\s*([\\d,]+\\.\\d{1,2})");
    private static final Pattern GRAND_TOTAL_CN = Pattern.compile(
            "\\(?大写\\)?\\s*[:：]?\\s*([零壹贰叁肆伍陆柒捌玖拾佰仟万亿元圆角分整正]{2,60})");
    private static final Pattern PAYEE = Pattern.compile("收款人\\s*[:：]?\\s*([^\\s\\n]{2,50})");
    private static final Pattern REVIEWER = Pattern.compile("复核(?:人)?\\s*[:：]?\\s*([^\\s\\n]{2,50})");
    private static final Pattern DRAWER = Pattern.compile("开票人\\s*[:：]?\\s*([^\\s\\n]{2,50})");

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern TAX_ID_VALID = Pattern.compile("^[0-9A-Z]{15,20}$");

    public ParsedInvoice parse(OcrRawResult raw, String typeHint) {
        ParsedInvoice invoice = new ParsedInvoice();
        String text = normalize(raw.fullText());
        List<LineInfo> lines = buildLines(raw, text);
        applyStructuredHints(invoice, raw.structuredFields());

        FieldValue code = findAcrossLines(lines, INVOICE_CODE, 1);
        fillString(invoice, FieldNames.INVOICE_CODE, code == null ? null : code.value(),
                code == null ? 0 : code.confidence(), ParsedInvoice::getInvoiceCode, ParsedInvoice::setInvoiceCode, 20);

        FieldValue number = findAcrossLines(lines, INVOICE_NUMBER, 1);
        fillString(invoice, FieldNames.INVOICE_NUMBER, number == null ? null : number.value(),
                number == null ? 0 : number.confidence(), ParsedInvoice::getInvoiceNumber,
                ParsedInvoice::setInvoiceNumber, 20);

        FieldValue date = findAcrossLines(lines, INVOICE_DATE, 1);
        if (date != null && invoice.getInvoiceDate() == null) {
            LocalDate parsed = parseDate(date.value());
            if (parsed != null) {
                invoice.setInvoiceDate(parsed);
                invoice.confidence(FieldNames.INVOICE_DATE, date.confidence());
            }
        }

        FieldValue checkCode = findAcrossLines(lines, CHECK_CODE, 1);
        fillString(invoice, FieldNames.CHECK_CODE, checkCode == null ? null : checkCode.value(),
                checkCode == null ? 0 : checkCode.confidence(), ParsedInvoice::getCheckCode,
                ParsedInvoice::setCheckCode, 20);

        FieldValue machine = findAcrossLines(lines, MACHINE_NUMBER, 1);
        fillString(invoice, FieldNames.MACHINE_NUMBER, machine == null ? null : machine.value(),
                machine == null ? 0 : machine.confidence(), ParsedInvoice::getMachineNumber,
                ParsedInvoice::setMachineNumber, 32);

        fillPartyFields(invoice, text, lines);
        fillAmounts(invoice, lines, text);
        fillFooter(invoice, lines);
        parseItems(invoice, lines);
        deriveMissingAmounts(invoice);
        fillInvoiceType(invoice, text, typeHint);
        validate(invoice);
        return invoice;
    }

    private void applyStructuredHints(ParsedInvoice invoice, Map<String, OcrFieldHint> hints) {
        if (hints == null || hints.isEmpty()) {
            return;
        }
        hintString(invoice, hints, "InvoiceCode", FieldNames.INVOICE_CODE,
                ParsedInvoice::setInvoiceCode, 20);
        hintString(invoice, hints, "InvoiceNum", FieldNames.INVOICE_NUMBER,
                ParsedInvoice::setInvoiceNumber, 20);
        hintString(invoice, hints, "CheckCode", FieldNames.CHECK_CODE,
                ParsedInvoice::setCheckCode, 20);
        hintString(invoice, hints, "MachineNum", FieldNames.MACHINE_NUMBER,
                ParsedInvoice::setMachineNumber, 32);
        hintString(invoice, hints, "PurchaserName", FieldNames.BUYER_NAME,
                ParsedInvoice::setBuyerName, 300);
        hintString(invoice, hints, "PurchaserRegisterNum", FieldNames.BUYER_TAX_ID,
                ParsedInvoice::setBuyerTaxId, 20);
        hintString(invoice, hints, "PurchaserAddress", FieldNames.BUYER_ADDRESS_PHONE,
                ParsedInvoice::setBuyerAddressPhone, 500);
        hintString(invoice, hints, "PurchaserBank", FieldNames.BUYER_BANK_ACCOUNT,
                ParsedInvoice::setBuyerBankAccount, 500);
        hintString(invoice, hints, "SellerName", FieldNames.SELLER_NAME,
                ParsedInvoice::setSellerName, 300);
        hintString(invoice, hints, "SellerRegisterNum", FieldNames.SELLER_TAX_ID,
                ParsedInvoice::setSellerTaxId, 20);
        hintString(invoice, hints, "SellerAddress", FieldNames.SELLER_ADDRESS_PHONE,
                ParsedInvoice::setSellerAddressPhone, 500);
        hintString(invoice, hints, "SellerBank", FieldNames.SELLER_BANK_ACCOUNT,
                ParsedInvoice::setSellerBankAccount, 500);
        hintString(invoice, hints, "AmountInWords", FieldNames.GRAND_TOTAL_CN,
                ParsedInvoice::setGrandTotalCn, 100);
        hintString(invoice, hints, "Payee", FieldNames.PAYEE, ParsedInvoice::setPayee, 100);
        hintString(invoice, hints, "Reviewer", FieldNames.REVIEWER_NAME,
                ParsedInvoice::setReviewerName, 100);
        hintString(invoice, hints, "NoteIssuer", FieldNames.DRAWER, ParsedInvoice::setDrawer, 100);
        hintTaxRate(invoice, hints, "CommodityTaxRate");

        OcrFieldHint dateHint = hints.get("InvoiceDate");
        if (dateHint != null && invoice.getInvoiceDate() == null) {
            LocalDate parsed = parseDate(dateHint.value());
            if (parsed != null) {
                invoice.setInvoiceDate(parsed);
                invoice.confidence(FieldNames.INVOICE_DATE, dateHint.confidence());
            }
        }
        hintAmount(invoice, hints, "TotalAmount", FieldNames.TOTAL_AMOUNT, ParsedInvoice::setTotalAmount);
        hintAmount(invoice, hints, "TotalTax", FieldNames.TOTAL_TAX, ParsedInvoice::setTotalTax);
        hintAmount(invoice, hints, "AmountInFiguers", FieldNames.GRAND_TOTAL, ParsedInvoice::setGrandTotal);

        OcrFieldHint typeHint = hints.get("InvoiceType");
        if (typeHint != null && invoice.getInvoiceType() == null) {
            InvoiceType type = InvoiceType.fromCode(typeHint.value());
            if (type == null) {
                type = detectType(typeHint.value());
            }
            if (type != null) {
                invoice.setInvoiceType(type);
                invoice.confidence(FieldNames.INVOICE_TYPE, typeHint.confidence());
            }
        }
    }

    private void hintString(ParsedInvoice invoice, Map<String, OcrFieldHint> hints, String key,
                            String field, BiConsumer<ParsedInvoice, String> setter, int maxLength) {
        OcrFieldHint hint = hints.get(key);
        if (hint == null || hint.value() == null || hint.value().isBlank()) {
            return;
        }
        setter.accept(invoice, truncate(clean(hint.value()), maxLength));
        invoice.confidence(field, hint.confidence());
    }

    private void hintAmount(ParsedInvoice invoice, Map<String, OcrFieldHint> hints, String key,
                            String field, BiConsumer<ParsedInvoice, BigDecimal> setter) {
        OcrFieldHint hint = hints.get(key);
        if (hint == null) {
            return;
        }
        BigDecimal value = parseAmount(hint.value());
        if (value != null) {
            setter.accept(invoice, value);
            invoice.confidence(field, hint.confidence());
        }
    }

    private void hintTaxRate(ParsedInvoice invoice, Map<String, OcrFieldHint> hints, String key) {
        OcrFieldHint hint = hints.get(key);
        if (hint == null || hint.value() == null || hint.value().isBlank()) {
            return;
        }
        String first = hint.value().split("[,，;；]")[0].trim();
        Matcher matcher = ANY_TAX_RATE.matcher(first);
        if (matcher.find()) {
            invoice.setTaxRate(matcher.group(1) + "%");
        } else if (!first.isBlank()) {
            invoice.setTaxRate(truncate(first, 32));
        }
        invoice.confidence(FieldNames.TAX_RATE, hint.confidence());
    }

    private void fillPartyFields(ParsedInvoice invoice, String text, List<LineInfo> lines) {
        int buyerIndex = text.indexOf("购买方");
        int sellerIndex = text.indexOf("销售方");
        String buyerScope;
        String sellerScope;
        if (buyerIndex >= 0 && sellerIndex > buyerIndex) {
            buyerScope = text.substring(buyerIndex, sellerIndex);
            sellerScope = text.substring(sellerIndex);
        } else if (sellerIndex >= 0 && buyerIndex > sellerIndex) {
            sellerScope = text.substring(sellerIndex, buyerIndex);
            buyerScope = text.substring(buyerIndex);
        } else if (buyerIndex >= 0) {
            buyerScope = text.substring(buyerIndex);
            sellerScope = buyerScope;
        } else if (sellerIndex >= 0) {
            sellerScope = text.substring(sellerIndex);
            buyerScope = text.substring(0, sellerIndex);
        } else {
            buyerScope = text;
            sellerScope = text;
        }

        FieldValue buyerName = extractScope(buyerScope, NAME, lines);
        FieldValue sellerName = extractScope(sellerScope, NAME, lines);
        FieldValue buyerTaxId = extractScope(buyerScope, TAX_ID, lines);
        FieldValue sellerTaxId = extractScope(sellerScope, TAX_ID, lines);
        FieldValue buyerAddress = extractScope(buyerScope, ADDRESS_PHONE, lines);
        FieldValue sellerAddress = extractScope(sellerScope, ADDRESS_PHONE, lines);
        FieldValue buyerBank = extractScope(buyerScope, BANK_ACCOUNT, lines);
        FieldValue sellerBank = extractScope(sellerScope, BANK_ACCOUNT, lines);

        List<FieldValue> allNames = findAll(NAME, text, lines);
        List<FieldValue> allTaxIds = findAll(TAX_ID, text, lines);
        List<FieldValue> allAddresses = findAll(ADDRESS_PHONE, text, lines);
        List<FieldValue> allBanks = findAll(BANK_ACCOUNT, text, lines);

        if (buyerName == null && !allNames.isEmpty()) {
            buyerName = allNames.get(0);
        }
        if (sellerName == null && allNames.size() > 1) {
            sellerName = allNames.get(1);
        }
        if (buyerTaxId == null && !allTaxIds.isEmpty()) {
            buyerTaxId = allTaxIds.get(0);
        }
        if (sellerTaxId == null && allTaxIds.size() > 1) {
            sellerTaxId = allTaxIds.get(1);
        }
        if (buyerAddress == null && !allAddresses.isEmpty()) {
            buyerAddress = allAddresses.get(0);
        }
        if (sellerAddress == null && allAddresses.size() > 1) {
            sellerAddress = allAddresses.get(1);
        }
        if (buyerBank == null && !allBanks.isEmpty()) {
            buyerBank = allBanks.get(0);
        }
        if (sellerBank == null && allBanks.size() > 1) {
            sellerBank = allBanks.get(1);
        }

        fillValue(invoice, FieldNames.BUYER_NAME, buyerName, ParsedInvoice::getBuyerName,
                ParsedInvoice::setBuyerName, 300, true);
        fillValue(invoice, FieldNames.BUYER_TAX_ID, buyerTaxId, ParsedInvoice::getBuyerTaxId,
                ParsedInvoice::setBuyerTaxId, 20, false);
        fillValue(invoice, FieldNames.BUYER_ADDRESS_PHONE, buyerAddress, ParsedInvoice::getBuyerAddressPhone,
                ParsedInvoice::setBuyerAddressPhone, 500, false);
        fillValue(invoice, FieldNames.BUYER_BANK_ACCOUNT, buyerBank, ParsedInvoice::getBuyerBankAccount,
                ParsedInvoice::setBuyerBankAccount, 500, false);
        fillValue(invoice, FieldNames.SELLER_NAME, sellerName, ParsedInvoice::getSellerName,
                ParsedInvoice::setSellerName, 300, true);
        fillValue(invoice, FieldNames.SELLER_TAX_ID, sellerTaxId, ParsedInvoice::getSellerTaxId,
                ParsedInvoice::setSellerTaxId, 20, false);
        fillValue(invoice, FieldNames.SELLER_ADDRESS_PHONE, sellerAddress, ParsedInvoice::getSellerAddressPhone,
                ParsedInvoice::setSellerAddressPhone, 500, false);
        fillValue(invoice, FieldNames.SELLER_BANK_ACCOUNT, sellerBank, ParsedInvoice::getSellerBankAccount,
                ParsedInvoice::setSellerBankAccount, 500, false);
    }

    private void fillAmounts(ParsedInvoice invoice, List<LineInfo> lines, String text) {
        FieldValue pair = findAcrossLines(lines, TOTAL_PAIR, 1);
        if (pair != null) {
            if (invoice.getTotalAmount() == null) {
                BigDecimal amount = parseAmount(pair.value());
                if (amount != null) {
                    invoice.setTotalAmount(amount);
                    invoice.confidence(FieldNames.TOTAL_AMOUNT, pair.confidence());
                }
            }
            BigDecimal tax = parseAmount(groupOf(lines, TOTAL_PAIR, 2));
            if (invoice.getTotalTax() == null && tax != null) {
                invoice.setTotalTax(tax);
                invoice.confidence(FieldNames.TOTAL_TAX, pair.confidence());
            }
        }

        FieldValue grand = findAcrossLines(lines, GRAND_TOTAL, 1);
        if (grand != null && invoice.getGrandTotal() == null) {
            BigDecimal amount = parseAmount(grand.value());
            if (amount != null) {
                invoice.setGrandTotal(amount);
                invoice.confidence(FieldNames.GRAND_TOTAL, grand.confidence());
            }
        }
        FieldValue grandCn = findAcrossLines(lines, GRAND_TOTAL_CN, 1);
        if (grandCn != null) {
            fillString(invoice, FieldNames.GRAND_TOTAL_CN, grandCn.value(), grandCn.confidence(),
                    ParsedInvoice::getGrandTotalCn, ParsedInvoice::setGrandTotalCn, 100);
        }

        if (invoice.getGrandTotal() == null) {
            BigDecimal fallback = findGrandTotalFallback(lines);
            if (fallback != null) {
                invoice.setGrandTotal(fallback);
                invoice.confidence(FieldNames.GRAND_TOTAL, 55);
            }
        }

        FieldValue rate = findAcrossLines(lines, TAX_RATE, 1);
        if (rate != null) {
            invoice.setTaxRate(rate.value() + "%");
            invoice.confidence(FieldNames.TAX_RATE, rate.confidence());
        } else {
            String commonRate = findMostCommonRate(text);
            if (commonRate != null) {
                invoice.setTaxRate(commonRate);
                invoice.confidence(FieldNames.TAX_RATE, 85);
            }
        }
    }

    private void fillFooter(ParsedInvoice invoice, List<LineInfo> lines) {
        FieldValue payee = findAcrossLines(lines, PAYEE, 1);
        fillString(invoice, FieldNames.PAYEE, payee == null ? null : payee.value(),
                payee == null ? 0 : payee.confidence(), ParsedInvoice::getPayee, ParsedInvoice::setPayee, 100);
        FieldValue reviewer = findAcrossLines(lines, REVIEWER, 1);
        fillString(invoice, FieldNames.REVIEWER_NAME, reviewer == null ? null : reviewer.value(),
                reviewer == null ? 0 : reviewer.confidence(), ParsedInvoice::getReviewerName,
                ParsedInvoice::setReviewerName, 100);
        FieldValue drawer = findAcrossLines(lines, DRAWER, 1);
        fillString(invoice, FieldNames.DRAWER, drawer == null ? null : drawer.value(),
                drawer == null ? 0 : drawer.confidence(), ParsedInvoice::getDrawer, ParsedInvoice::setDrawer, 100);
    }

    private void parseItems(ParsedInvoice invoice, List<LineInfo> lines) {
        int headerIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            String value = lines.get(i).text();
            if (value.contains("货物或应税劳务") || value.contains("项目名称") || value.contains("服务名称")) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            return;
        }
        int endIndex = lines.size();
        for (int i = headerIndex + 1; i < lines.size(); i++) {
            if (lines.get(i).text().matches("^\\s*合\\s*计.*")) {
                endIndex = i;
                break;
            }
        }
        int lineNo = 1;
        for (int i = headerIndex + 1; i < endIndex; i++) {
            String line = lines.get(i).text();
            if (line.isBlank() || !line.matches(".*[\\u4e00-\\u9fa5].*")) {
                continue;
            }
            Matcher rateMatcher = ANY_TAX_RATE.matcher(line);
            if (!rateMatcher.find()) {
                continue;
            }
            String rate = rateMatcher.group(1) + "%";
            String withoutRate = line.replace(rateMatcher.group(), "");
            Matcher numbers = NUMBER.matcher(withoutRate);
            List<BigDecimal> values = new ArrayList<>();
            int firstNumber = -1;
            while (numbers.find()) {
                if (firstNumber < 0) {
                    firstNumber = numbers.start();
                }
                BigDecimal value = parseAmount(numbers.group());
                if (value != null) {
                    values.add(value);
                }
            }
            if (values.size() < 2 || firstNumber <= 0) {
                continue;
            }
            String name = withoutRate.substring(0, firstNumber).replaceAll("\\s+", " ").trim();
            if (name.length() < 2) {
                continue;
            }
            ParsedItem item = new ParsedItem();
            item.setLineNo(lineNo++);
            item.setItemName(truncate(name, 500));
            item.setTaxRate(rate);
            if (values.size() >= 4) {
                item.setQuantity(values.get(0));
                item.setUnitPrice(values.get(1));
                item.setAmount(values.get(2).setScale(2, RoundingMode.HALF_UP));
                item.setTaxAmount(values.get(3).setScale(2, RoundingMode.HALF_UP));
            } else if (values.size() == 3) {
                item.setQuantity(values.get(0));
                item.setUnitPrice(values.get(1));
                item.setAmount(values.get(2).setScale(2, RoundingMode.HALF_UP));
                item.setTaxAmount(computeTax(item.getAmount(), rate));
            } else {
                item.setAmount(values.get(0).setScale(2, RoundingMode.HALF_UP));
                item.setTaxAmount(values.get(1).setScale(2, RoundingMode.HALF_UP));
            }
            invoice.getItems().add(item);
        }
    }

    private BigDecimal computeTax(BigDecimal amount, String rate) {
        if (amount == null || rate == null) {
            return null;
        }
        try {
            BigDecimal percent = new BigDecimal(rate.replace("%", "").trim());
            return amount.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void deriveMissingAmounts(ParsedInvoice invoice) {
        if (invoice.getTotalAmount() == null && invoice.getTotalTax() == null && !invoice.getItems().isEmpty()) {
            BigDecimal amount = invoice.getItems().stream().map(ParsedItem::getAmount)
                    .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal tax = invoice.getItems().stream().map(ParsedItem::getTaxAmount)
                    .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (amount.signum() != 0) {
                invoice.setTotalAmount(amount.setScale(2, RoundingMode.HALF_UP));
                invoice.confidence(FieldNames.TOTAL_AMOUNT, 60);
            }
            if (tax.signum() != 0) {
                invoice.setTotalTax(tax.setScale(2, RoundingMode.HALF_UP));
                invoice.confidence(FieldNames.TOTAL_TAX, 60);
            }
        }
        if (invoice.getTotalAmount() == null && invoice.getGrandTotal() != null && invoice.getTotalTax() != null) {
            invoice.setTotalAmount(invoice.getGrandTotal().subtract(invoice.getTotalTax()).setScale(2, RoundingMode.HALF_UP));
            invoice.confidence(FieldNames.TOTAL_AMOUNT, 60);
        }
        if (invoice.getTotalTax() == null && invoice.getGrandTotal() != null && invoice.getTotalAmount() != null) {
            invoice.setTotalTax(invoice.getGrandTotal().subtract(invoice.getTotalAmount()).setScale(2, RoundingMode.HALF_UP));
            invoice.confidence(FieldNames.TOTAL_TAX, 60);
        }
        if (invoice.getGrandTotal() == null && invoice.getTotalAmount() != null && invoice.getTotalTax() != null) {
            invoice.setGrandTotal(invoice.getTotalAmount().add(invoice.getTotalTax()).setScale(2, RoundingMode.HALF_UP));
            invoice.confidence(FieldNames.GRAND_TOTAL, 60);
        }
        if (invoice.getGrandTotal() != null && invoice.getTotalAmount() != null && invoice.getTotalTax() != null) {
            BigDecimal sum = invoice.getTotalAmount().add(invoice.getTotalTax());
            if (sum.subtract(invoice.getGrandTotal()).abs().compareTo(new BigDecimal("0.01")) > 0) {
                invoice.getWarnings().add("金额校验不一致：不含税金额+税额≠价税合计");
            }
        }
    }

    private void fillInvoiceType(ParsedInvoice invoice, String text, String typeHint) {
        if (invoice.getInvoiceType() != null) {
            return;
        }
        InvoiceType hintType = InvoiceType.fromCode(typeHint);
        if (hintType != null) {
            invoice.setInvoiceType(hintType);
            invoice.confidence(FieldNames.INVOICE_TYPE, 95);
            return;
        }
        InvoiceType detected = detectType(text, invoice);
        invoice.setInvoiceType(detected == null ? InvoiceType.OTHER : detected);
        invoice.confidence(FieldNames.INVOICE_TYPE, detected == null || detected == InvoiceType.OTHER ? 40 : 85);
    }

    private InvoiceType detectType(String typeValue) {
        if (typeValue == null) {
            return InvoiceType.OTHER;
        }
        String compact = typeValue.replaceAll("\\s+", "");
        if (compact.contains("卷票")) {
            return InvoiceType.VAT_ROLL;
        }
        if (compact.contains("全电") || compact.contains("数电票")) {
            return InvoiceType.DIGITAL;
        }
        if (compact.contains("增值税电子普通发票") || compact.contains("电子普通发票")) {
            return InvoiceType.VAT_E_NORMAL;
        }
        if (compact.contains("增值税专用发票") || compact.contains("专用发票")) {
            return InvoiceType.VAT_SPECIAL;
        }
        if (compact.contains("增值税普通发票") || compact.contains("普通发票")) {
            return InvoiceType.VAT_NORMAL;
        }
        return InvoiceType.OTHER;
    }

    private InvoiceType detectType(String text, ParsedInvoice invoice) {
        if (invoice.getInvoiceCode() == null && invoice.getInvoiceNumber() != null
                && invoice.getInvoiceNumber().length() == 20) {
            return InvoiceType.DIGITAL;
        }
        return detectType(text);
    }

    private void validate(ParsedInvoice invoice) {
        validateTaxId(invoice, FieldNames.BUYER_TAX_ID, invoice.getBuyerTaxId());
        validateTaxId(invoice, FieldNames.SELLER_TAX_ID, invoice.getSellerTaxId());
        if (invoice.getBuyerTaxId() != null) {
            invoice.setBuyerTaxId(invoice.getBuyerTaxId().toUpperCase(Locale.ROOT));
        }
        if (invoice.getSellerTaxId() != null) {
            invoice.setSellerTaxId(invoice.getSellerTaxId().toUpperCase(Locale.ROOT));
        }
        if (invoice.getInvoiceNumber() != null) {
            int length = invoice.getInvoiceNumber().length();
            if (length != 8 && length != 20) {
                invoice.getWarnings().add("发票号码长度异常（" + length + " 位）");
                lowerConfidence(invoice, FieldNames.INVOICE_NUMBER, 55);
            }
        }
        if (invoice.getCheckCode() != null) {
            String compactCheckCode = invoice.getCheckCode().replaceAll("\\s+", "");
            invoice.setCheckCode(truncate(compactCheckCode, 20));
        }
        if (invoice.getInvoiceCode() != null && invoice.getInvoiceCode().length() != 10
                && invoice.getInvoiceCode().length() != 12) {
            invoice.getWarnings().add("发票代码长度异常（" + invoice.getInvoiceCode().length() + " 位）");
            lowerConfidence(invoice, FieldNames.INVOICE_CODE, 55);
        }
        if (invoice.getInvoiceType() == InvoiceType.DIGITAL && invoice.getInvoiceCode() != null) {
            // digital invoices do not carry a code; a code here is suspicious but not fatal
            invoice.getWarnings().add("全电发票不应存在发票代码，请复核");
        }
        if (invoice.getInvoiceDate() == null) {
            invoice.getWarnings().add("未识别到开票日期");
        }
    }

    private void validateTaxId(ParsedInvoice invoice, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!TAX_ID_VALID.matcher(normalized).matches()) {
            invoice.getWarnings().add(FieldNames.LABELS.get(field) + "格式不正确");
            lowerConfidence(invoice, field, 45);
        }
    }

    private void lowerConfidence(ParsedInvoice invoice, String field, int maximum) {
        Integer current = invoice.getFieldConfidence().get(field);
        if (current == null) {
            invoice.confidence(field, maximum);
        } else if (current > maximum) {
            invoice.confidence(field, maximum);
        }
    }

    private FieldValue findAcrossLines(List<LineInfo> lines, Pattern pattern, int group) {
        FieldValue direct = findInLines(lines, pattern, group);
        if (direct != null) {
            return direct;
        }
        String full = lines.stream().map(LineInfo::text).reduce((a, b) -> a + "\n" + b).orElse("");
        Matcher matcher = pattern.matcher(full);
        if (matcher.find()) {
            return new FieldValue(clean(matcher.group(group)), 65);
        }
        return null;
    }

    private FieldValue findInLines(List<LineInfo> lines, Pattern pattern, int group) {
        for (LineInfo line : lines) {
            Matcher matcher = pattern.matcher(line.text());
            if (matcher.find()) {
                String value = clean(matcher.group(group));
                if (!value.isBlank()) {
                    return new FieldValue(value, line.confidence());
                }
            }
        }
        return null;
    }

    private List<FieldValue> findAll(Pattern pattern, String text, List<LineInfo> lines) {
        List<FieldValue> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = clean(matcher.group(1));
            if (!value.isBlank()) {
                values.add(new FieldValue(value, confidenceForValue(lines, value)));
            }
        }
        return values;
    }

    private FieldValue extractScope(String scope, Pattern pattern, List<LineInfo> lines) {
        Matcher matcher = pattern.matcher(scope);
        if (matcher.find()) {
            String value = clean(matcher.group(1));
            if (!value.isBlank()) {
                return new FieldValue(value, confidenceForValue(lines, value));
            }
        }
        return null;
    }

    private double confidenceForValue(List<LineInfo> lines, String value) {
        if (value == null || value.length() < 2) {
            return 85;
        }
        for (LineInfo line : lines) {
            if (line.text().contains(value)) {
                return line.confidence();
            }
        }
        return 70;
    }

    private void fillValue(ParsedInvoice invoice, String field, FieldValue value,
                           Function<ParsedInvoice, String> getter,
                           BiConsumer<ParsedInvoice, String> setter,
                           int maxLength, boolean isName) {
        if (value == null) {
            return;
        }
        String current = getter.apply(invoice);
        if (current != null && !current.isBlank()) {
            return;
        }
        String cleaned = clean(value.value());
        if (isName) {
            cleaned = cleaned.replaceAll("\\s+", " ").trim();
        }
        if (cleaned.isBlank()) {
            return;
        }
        setter.accept(invoice, truncate(cleaned, maxLength));
        invoice.confidence(field, value.confidence());
    }

    private void fillString(ParsedInvoice invoice, String field, String value, double confidence,
                            Function<ParsedInvoice, String> getter,
                            BiConsumer<ParsedInvoice, String> setter, int maxLength) {
        if (value == null || value.isBlank()) {
            return;
        }
        String current = getter.apply(invoice);
        if (current != null && !current.isBlank()) {
            return;
        }
        setter.accept(invoice, truncate(clean(value), maxLength));
        invoice.confidence(field, confidence);
    }

    private String groupOf(List<LineInfo> lines, Pattern pattern, int group) {
        for (LineInfo line : lines) {
            Matcher matcher = pattern.matcher(line.text());
            if (matcher.find()) {
                return matcher.group(group);
            }
        }
        return null;
    }

    private BigDecimal findGrandTotalFallback(List<LineInfo> lines) {
        for (LineInfo line : lines) {
            if (!line.text().contains("价税合计")) {
                continue;
            }
            Matcher matcher = NUMBER.matcher(line.text());
            BigDecimal last = null;
            while (matcher.find()) {
                BigDecimal value = parseAmount(matcher.group());
                if (value != null) {
                    last = value;
                }
            }
            if (last != null) {
                return last.setScale(2, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    private String findMostCommonRate(String text) {
        Matcher matcher = ANY_TAX_RATE.matcher(text);
        Map<String, Integer> counts = new LinkedHashMap<>();
        while (matcher.find()) {
            counts.merge(matcher.group(1) + "%", 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s+", "");
        try {
            Matcher chinese = Pattern.compile("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})").matcher(compact);
            if (chinese.find()) {
                return LocalDate.of(Integer.parseInt(chinese.group(1)),
                        Integer.parseInt(chinese.group(2)), Integer.parseInt(chinese.group(3)));
            }
            if (compact.matches("\\d{8}")) {
                return LocalDate.of(Integer.parseInt(compact.substring(0, 4)),
                        Integer.parseInt(compact.substring(4, 6)), Integer.parseInt(compact.substring(6, 8)));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private BigDecimal parseAmount(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[￥¥,\\s]", "");
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(' ', ' ').replaceAll("[\\t\\r]+", " ").trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        return normalized.replace('﻿', ' ').replace(' ', ' ');
    }

    private List<LineInfo> buildLines(OcrRawResult raw, String normalizedText) {
        List<LineInfo> lines = new ArrayList<>();
        if (raw.blocks() != null && !raw.blocks().isEmpty()) {
            for (OcrTextBlock block : raw.blocks()) {
                if (block.text() == null || block.text().isBlank()) {
                    continue;
                }
                String normalized = normalize(block.text());
                for (String part : normalized.split("\\R")) {
                    if (!part.isBlank()) {
                        lines.add(new LineInfo(part, block.confidence()));
                    }
                }
            }
            if (!lines.isEmpty()) {
                return lines;
            }
        }
        for (String part : normalizedText.split("\\R")) {
            if (!part.isBlank()) {
                lines.add(new LineInfo(part, 100));
            }
        }
        return lines;
    }

    private record LineInfo(String text, double confidence) {
    }

    private record FieldValue(String value, double confidence) {
    }
}
