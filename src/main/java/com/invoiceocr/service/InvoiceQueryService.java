package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.InvoiceStatus;
import com.invoiceocr.domain.InvoiceType;
import com.invoiceocr.domain.OcrTask;
import com.invoiceocr.domain.VerificationStatus;
import com.invoiceocr.dto.CommonDtos;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.dto.InvoiceQuery;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.OcrTaskRepository;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.web.ApiException;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class InvoiceQueryService {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "invoiceDate",
            "grandTotal", "invoiceNumber", "status");

    private final InvoiceRepository invoiceRepository;
    private final OcrTaskRepository taskRepository;
    private final InvoiceAssembler assembler;
    private final CurrentUser currentUser;
    private final InvoiceAccessService accessService;

    public CommonDtos.PageResponse<InvoiceDtos.InvoiceListItem> search(InvoiceQuery query) {
        Page<Invoice> page = invoiceRepository.findAll(buildSpecification(query, false),
                pageRequest(query));
        return CommonDtos.PageResponse.of(page, assembler::toListItem);
    }

    public CommonDtos.PageResponse<InvoiceDtos.InvoiceListItem> reviewQueue(InvoiceQuery query) {
        Specification<Invoice> queue = (root, cq, cb) -> cb.or(
                cb.equal(root.get("status"), InvoiceStatus.NEEDS_REVIEW),
                cb.isTrue(root.get("duplicateFlag")),
                root.get("verificationStatus").in(VerificationStatus.INCONSISTENT,
                        VerificationStatus.NOT_FOUND, VerificationStatus.INTERFACE_ERROR));
        Specification<Invoice> spec = buildSpecification(query, true).and(queue);
        Page<Invoice> page = invoiceRepository.findAll(spec, pageRequest(query));
        return CommonDtos.PageResponse.of(page, assembler::toListItem);
    }

    public Object get(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId).orElse(null);
        if (invoice != null) {
            accessService.checkInvoiceView(invoice);
            return assembler.toDetail(invoice);
        }
        OcrTask task = taskRepository.findTopByInvoiceIdOrderByCreatedAtDesc(invoiceId).orElse(null);
        if (task != null) {
            accessService.checkTaskView(task);
            return assembler.toUploadStatus(task);
        }
        throw ApiException.notFound("发票或识别任务不存在");
    }

    public Invoice requireInvoice(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ApiException.notFound("发票不存在"));
        accessService.checkInvoiceView(invoice);
        return invoice;
    }

    public List<Invoice> listForExport(InvoiceQuery query, int maxRows) {
        InvoiceQuery copy = copyQuery(query);
        int size = Math.min(Math.max(maxRows, 1), 10000);
        String sortBy = copy.getSortBy() == null || !SORTABLE.contains(copy.getSortBy())
                ? "createdAt" : copy.getSortBy();
        Sort.Direction direction = "asc".equalsIgnoreCase(copy.getSortDir())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        Page<Invoice> page = invoiceRepository.findAll(buildSpecification(copy, false),
                PageRequest.of(0, size, Sort.by(direction, sortBy)));
        return page.getContent();
    }

    private Specification<Invoice> buildSpecification(InvoiceQuery query, boolean ignoreStatusForQueue) {
        UserPrincipal principal = currentUser.require();
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!currentUser.isPrivileged()) {
                predicates.add(cb.equal(root.get("ownerId"), principal.id()));
            }
            if (query.getId() != null && !query.getId().isBlank()) {
                predicates.add(cb.equal(root.get("id"), query.getId().trim()));
            }
            if (query.getInvoiceCode() != null && !query.getInvoiceCode().isBlank()) {
                predicates.add(cb.equal(root.get("invoiceCode"), query.getInvoiceCode().trim()));
            }
            if (query.getInvoiceNumber() != null && !query.getInvoiceNumber().isBlank()) {
                predicates.add(cb.equal(root.get("invoiceNumber"), query.getInvoiceNumber().trim()));
            }
            if (query.getInvoiceDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("invoiceDate"), query.getInvoiceDateFrom()));
            }
            if (query.getInvoiceDateTo() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("invoiceDate"), query.getInvoiceDateTo()));
            }
            if (query.getSellerName() != null && !query.getSellerName().isBlank()) {
                predicates.add(cb.like(root.get("sellerName"), "%" + query.getSellerName().trim() + "%"));
            }
            if (query.getSellerTaxId() != null && !query.getSellerTaxId().isBlank()) {
                predicates.add(cb.equal(root.get("sellerTaxId"), query.getSellerTaxId().trim().toUpperCase(Locale.ROOT)));
            }
            if (query.getBuyerName() != null && !query.getBuyerName().isBlank()) {
                predicates.add(cb.like(root.get("buyerName"), "%" + query.getBuyerName().trim() + "%"));
            }
            if (query.getBuyerTaxId() != null && !query.getBuyerTaxId().isBlank()) {
                predicates.add(cb.equal(root.get("buyerTaxId"), query.getBuyerTaxId().trim().toUpperCase(Locale.ROOT)));
            }
            if (query.getAmountMin() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("grandTotal"), query.getAmountMin()));
            }
            if (query.getAmountMax() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("grandTotal"), query.getAmountMax()));
            }
            if (query.getInvoiceType() != null && !query.getInvoiceType().isBlank()) {
                predicates.add(cb.equal(root.get("invoiceType"), parseType(query.getInvoiceType())));
            }
            if (query.getStatus() != null && !query.getStatus().isBlank() && !ignoreStatusForQueue) {
                predicates.add(cb.equal(root.get("status"), parseStatus(query.getStatus())));
            }
            if (query.getDuplicateFlag() != null) {
                predicates.add(cb.equal(root.get("duplicateFlag"), query.getDuplicateFlag()));
            }
            if (query.getVerificationStatus() != null && !query.getVerificationStatus().isBlank()) {
                predicates.add(cb.equal(root.get("verificationStatus"),
                        parseVerification(query.getVerificationStatus())));
            }
            if (query.getUploadFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.getUploadFrom().atStartOfDay()));
            }
            if (query.getUploadTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), query.getUploadTo().plusDays(1).atStartOfDay()));
            }
            if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
                String keyword = "%" + query.getKeyword().trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("invoiceNumber"), keyword),
                        cb.like(root.get("invoiceCode"), keyword),
                        cb.like(root.get("buyerName"), keyword),
                        cb.like(root.get("sellerName"), keyword),
                        cb.like(root.get("buyerTaxId"), keyword),
                        cb.like(root.get("sellerTaxId"), keyword)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private PageRequest pageRequest(InvoiceQuery query) {
        int page = Math.max(query.getPage() == null ? 0 : query.getPage(), 0);
        int size = Math.min(Math.max(query.getSize() == null ? 20 : query.getSize(), 1), 200);
        String sortBy = query.getSortBy() == null || !SORTABLE.contains(query.getSortBy())
                ? "createdAt" : query.getSortBy();
        Sort.Direction direction = "asc".equalsIgnoreCase(query.getSortDir())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(page, size, Sort.by(direction, sortBy));
    }

    private InvoiceType parseType(String value) {
        InvoiceType type = InvoiceType.fromCode(value);
        if (type == null) {
            throw ApiException.badRequest("未知发票类型：" + value);
        }
        return type;
    }

    private InvoiceStatus parseStatus(String value) {
        try {
            return InvoiceStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知状态：" + value);
        }
    }

    private VerificationStatus parseVerification(String value) {
        try {
            return VerificationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("未知验真状态：" + value);
        }
    }

    private InvoiceQuery copyQuery(InvoiceQuery source) {
        InvoiceQuery copy = new InvoiceQuery();
        copy.setId(source.getId());
        copy.setInvoiceCode(source.getInvoiceCode());
        copy.setInvoiceNumber(source.getInvoiceNumber());
        copy.setInvoiceDateFrom(source.getInvoiceDateFrom());
        copy.setInvoiceDateTo(source.getInvoiceDateTo());
        copy.setSellerName(source.getSellerName());
        copy.setSellerTaxId(source.getSellerTaxId());
        copy.setBuyerName(source.getBuyerName());
        copy.setBuyerTaxId(source.getBuyerTaxId());
        copy.setAmountMin(source.getAmountMin());
        copy.setAmountMax(source.getAmountMax());
        copy.setInvoiceType(source.getInvoiceType());
        copy.setStatus(source.getStatus());
        copy.setDuplicateFlag(source.getDuplicateFlag());
        copy.setVerificationStatus(source.getVerificationStatus());
        copy.setUploadFrom(source.getUploadFrom());
        copy.setUploadTo(source.getUploadTo());
        copy.setKeyword(source.getKeyword());
        copy.setSortBy(source.getSortBy());
        copy.setSortDir(source.getSortDir());
        return copy;
    }
}
