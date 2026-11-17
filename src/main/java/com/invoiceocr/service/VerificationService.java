package com.invoiceocr.service;

import com.invoiceocr.domain.Invoice;
import com.invoiceocr.domain.Role;
import com.invoiceocr.domain.VerificationRecord;
import com.invoiceocr.domain.VerificationStatus;
import com.invoiceocr.dto.InvoiceDtos;
import com.invoiceocr.repo.InvoiceRepository;
import com.invoiceocr.repo.VerificationRecordRepository;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.util.HashUtil;
import com.invoiceocr.verify.InvoiceVerificationClient;
import com.invoiceocr.verify.InvoiceVerifyRequest;
import com.invoiceocr.verify.VerificationResult;
import com.invoiceocr.web.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private final InvoiceRepository invoiceRepository;
    private final VerificationRecordRepository verificationRepository;
    private final SystemConfigService configService;
    private final List<InvoiceVerificationClient> clients;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public InvoiceDtos.VerifyResponse verifyManually(String invoiceId) {
        UserPrincipal principal = currentUser.require();
        InvoiceDtos.VerifyResponse response = verify(invoiceId, principal.username(), principal.role());
        return response;
    }

    @Async("ocrExecutor")
    public void verifyAsync(String invoiceId, String actor) {
        if (!inFlight.add(invoiceId)) {
            return;
        }
        try {
            verify(invoiceId, actor, null);
        } catch (Exception e) {
            log.warn("Async verification failed for {}: {}", invoiceId, e.getMessage());
        } finally {
            inFlight.remove(invoiceId);
        }
    }

    public void maybeAutoVerify(Invoice invoice, boolean needsReview) {
        String trigger = configService.get(SystemConfigService.VERIFY_TRIGGER, "MANUAL");
        boolean run = "AUTO".equalsIgnoreCase(trigger)
                || ("REVIEW_ONLY".equalsIgnoreCase(trigger) && needsReview);
        if (run) {
            verifyAsync(invoice.getId(), "system");
        }
    }

    private InvoiceDtos.VerifyResponse verify(String invoiceId, String actor, Role role) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ApiException.notFound("发票不存在"));
        if (invoice.getStatus() == com.invoiceocr.domain.InvoiceStatus.PROCESSING) {
            throw ApiException.conflict("发票正在识别中，暂不能验真");
        }
        InvoiceVerifyRequest request = new InvoiceVerifyRequest(
                invoice.getInvoiceCode(), invoice.getInvoiceNumber(), invoice.getInvoiceDate(),
                invoice.getCheckCode(), invoice.getTotalAmount(),
                invoice.getInvoiceType() == null ? null : invoice.getInvoiceType().name(),
                invoice.getGrandTotal());
        String payloadHash = HashUtil.sha256Hex(String.join("|",
                nullSafe(request.invoiceCode()), nullSafe(request.invoiceNumber()),
                nullSafe(request.invoiceDate() == null ? "" : request.invoiceDate().toString()),
                nullSafe(request.checkCode()),
                nullSafe(request.amountWithoutTax() == null ? "" : request.amountWithoutTax().toPlainString()),
                nullSafe(request.invoiceType())));

        String channelName = configService.get(SystemConfigService.VERIFY_CHANNEL, "mock");
        InvoiceVerificationClient client = clients.stream()
                .filter(candidate -> candidate.name().equals(channelName))
                .findFirst()
                .orElse(null);
        VerificationResult result;
        if (client == null) {
            result = new VerificationResult(VerificationStatus.INTERFACE_ERROR, channelName,
                    "未知验真渠道：" + channelName);
        } else {
            try {
                result = client.verify(request);
            } catch (Exception e) {
                result = new VerificationResult(VerificationStatus.INTERFACE_ERROR, channelName,
                        "验真渠道异常：" + e.getMessage());
            }
        }

        VerificationRecord record = new VerificationRecord();
        record.setInvoiceId(invoiceId);
        record.setChannel(result.channel() == null ? channelName : result.channel());
        record.setRequestPayloadHash(payloadHash);
        record.setResultStatus(result.status());
        record.setResultSummary(result.summary());
        record.setVerifiedAt(LocalDateTime.now());
        verificationRepository.save(record);

        invoice.setVerificationStatus(result.status());
        invoice.setVerifiedAt(record.getVerifiedAt());
        invoice.setUpdatedAt(LocalDateTime.now());
        invoiceRepository.save(invoice);

        UserPrincipal principal = currentUser.getOrNull();
        if (principal != null) {
            auditService.log("VERIFY", "INVOICE", invoiceId,
                    "channel=" + record.getChannel() + ", status=" + result.status());
        } else {
            auditService.logAs(null, actor, role, "VERIFY", "INVOICE", invoiceId, null,
                    "channel=" + record.getChannel() + ", status=" + result.status());
        }
        return new InvoiceDtos.VerifyResponse(invoiceId, result.status().name(), result.status().getLabel(),
                result.summary(), record.getVerifiedAt());
    }

    public List<InvoiceDtos.VerificationDto> history(String invoiceId) {
        return verificationRepository.findByInvoiceIdOrderByVerifiedAtDesc(invoiceId).stream()
                .map(record -> new InvoiceDtos.VerificationDto(record.getId(), record.getChannel(),
                        record.getResultStatus().name(), record.getResultStatus().getLabel(),
                        record.getResultSummary(), record.getVerifiedAt()))
                .collect(Collectors.toList());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
