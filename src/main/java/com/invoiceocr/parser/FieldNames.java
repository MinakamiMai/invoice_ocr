package com.invoiceocr.parser;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FieldNames {

    public static final String INVOICE_TYPE = "invoiceType";
    public static final String INVOICE_CODE = "invoiceCode";
    public static final String INVOICE_NUMBER = "invoiceNumber";
    public static final String INVOICE_DATE = "invoiceDate";
    public static final String MACHINE_NUMBER = "machineNumber";
    public static final String BUYER_NAME = "buyerName";
    public static final String BUYER_TAX_ID = "buyerTaxId";
    public static final String BUYER_ADDRESS_PHONE = "buyerAddressPhone";
    public static final String BUYER_BANK_ACCOUNT = "buyerBankAccount";
    public static final String SELLER_NAME = "sellerName";
    public static final String SELLER_TAX_ID = "sellerTaxId";
    public static final String SELLER_ADDRESS_PHONE = "sellerAddressPhone";
    public static final String SELLER_BANK_ACCOUNT = "sellerBankAccount";
    public static final String TOTAL_AMOUNT = "totalAmount";
    public static final String TOTAL_TAX = "totalTax";
    public static final String GRAND_TOTAL = "grandTotal";
    public static final String GRAND_TOTAL_CN = "grandTotalCn";
    public static final String TAX_RATE = "taxRate";
    public static final String CHECK_CODE = "checkCode";
    public static final String PAYEE = "payee";
    public static final String REVIEWER_NAME = "reviewerName";
    public static final String DRAWER = "drawer";

    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(INVOICE_TYPE, "发票类型");
        LABELS.put(INVOICE_CODE, "发票代码");
        LABELS.put(INVOICE_NUMBER, "发票号码");
        LABELS.put(INVOICE_DATE, "开票日期");
        LABELS.put(MACHINE_NUMBER, "机器编号");
        LABELS.put(BUYER_NAME, "购买方名称");
        LABELS.put(BUYER_TAX_ID, "购买方纳税人识别号");
        LABELS.put(BUYER_ADDRESS_PHONE, "购买方地址电话");
        LABELS.put(BUYER_BANK_ACCOUNT, "购买方开户行及账号");
        LABELS.put(SELLER_NAME, "销售方名称");
        LABELS.put(SELLER_TAX_ID, "销售方纳税人识别号");
        LABELS.put(SELLER_ADDRESS_PHONE, "销售方地址电话");
        LABELS.put(SELLER_BANK_ACCOUNT, "销售方开户行及账号");
        LABELS.put(TOTAL_AMOUNT, "合计金额（不含税）");
        LABELS.put(TOTAL_TAX, "合计税额");
        LABELS.put(GRAND_TOTAL, "价税合计（小写）");
        LABELS.put(GRAND_TOTAL_CN, "价税合计（大写）");
        LABELS.put(TAX_RATE, "税率");
        LABELS.put(CHECK_CODE, "校验码");
        LABELS.put(PAYEE, "收款人");
        LABELS.put(REVIEWER_NAME, "复核人");
        LABELS.put(DRAWER, "开票人");
    }

    private FieldNames() {
    }
}
