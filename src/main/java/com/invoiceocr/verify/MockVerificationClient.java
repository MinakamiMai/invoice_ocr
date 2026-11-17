package com.invoiceocr.verify;

import com.invoiceocr.domain.VerificationStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Development verification channel. It is intentionally NOT a real tax-platform lookup:
 * it only checks that all six elements are present and labels the result clearly.
 */
@Component
public class MockVerificationClient implements InvoiceVerificationClient {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public VerificationResult verify(InvoiceVerifyRequest request) {
        List<String> missing = new ArrayList<>();
        if (request.invoiceCode() == null || request.invoiceCode().isBlank()) {
            missing.add("发票代码");
        }
        if (request.invoiceNumber() == null || request.invoiceNumber().isBlank()) {
            missing.add("发票号码");
        }
        if (request.invoiceDate() == null) {
            missing.add("开票日期");
        }
        if (request.checkCode() == null || request.checkCode().isBlank()) {
            missing.add("校验码");
        }
        if (request.amountWithoutTax() == null) {
            missing.add("不含税金额");
        }
        if (request.invoiceType() == null || request.invoiceType().isBlank()) {
            missing.add("发票类型");
        }
        if (missing.isEmpty()) {
            return new VerificationResult(VerificationStatus.CONSISTENT, "mock",
                    "模拟验真：六要素齐全。（该渠道不连接税务平台，结果仅供联调，不构成真实验真结论。）");
        }
        return new VerificationResult(VerificationStatus.INCONSISTENT, "mock",
                "模拟验真：缺少要素 " + String.join("、", missing)
                        + "。（该渠道不连接税务平台，结果仅供联调。）");
    }
}
