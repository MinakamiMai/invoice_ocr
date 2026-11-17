package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InvoiceAccessService {

    private final CurrentUser currentUser;

    public void checkInvoiceView(Invoice invoice) {
        UserPrincipal principal = currentUser.require();
        if (currentUser.isPrivileged()) {
            return;
        }
        if (!invoice.getOwnerId().equals(principal.id())) {
            throw ApiException.forbidden("提交人员只能查看自己上传的发票");
        }
    }

    public void checkTaskView(OcrTask task) {
        UserPrincipal principal = currentUser.require();
        if (currentUser.isPrivileged()) {
            return;
        }
        if (!task.getOwnerId().equals(principal.id())) {
            throw ApiException.forbidden("提交人员只能查看自己上传的发票");
        }
    }

    public boolean canView(Invoice invoice) {
        UserPrincipal principal = currentUser.require();
        return currentUser.isPrivileged() || invoice.getOwnerId().equals(principal.id());
    }
}
