package com.invoiceocr.web;

import com.invoiceocr.dto.CommonDtos;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.dto.InvoiceQuery;
import com.invoiceocr.service.InvoiceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-tasks")
@RequiredArgsConstructor
public class ReviewController {

    private final InvoiceQueryService queryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','ADMIN')")
    public CommonDtos.PageResponse<InvoiceDtos.InvoiceListItem> queue(@ModelAttribute InvoiceQuery query) {
        return queryService.reviewQueue(query);
    }
}
