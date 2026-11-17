package com.invoiceocr.service;

import com.invoiceocr.domain.AuditLog;
import com.invoiceocr.domain.Role;
import com.invoiceocr.dto.AuditQuery;
import com.invoiceocr.repo.AuditLogRepository;
import com.invoiceocr.security.CurrentUser;
import com.invoiceocr.security.UserPrincipal;
import com.invoiceocr.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final CurrentUser currentUser;

    public void log(String action, String objectType, String objectId, String detail) {
        UserPrincipal principal = currentUser.getOrNull();
        logAs(principal == null ? null : principal.id(),
                principal == null ? null : principal.username(),
                principal == null ? null : principal.role(),
                action, objectType, objectId, currentIp(), detail);
    }

    public void logAs(Long userId, String username, Role role, String action,
                      String objectType, String objectId, String ip, String detail) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setRole(role == null ? null : role.name());
        log.setAction(action);
        log.setObjectType(objectType);
        log.setObjectId(objectId);
        log.setIp(ip);
        log.setDetail(truncate(detail, 8000));
        log.setCreatedAt(LocalDateTime.now());
        auditLogRepository.save(log);
    }

    public Page<AuditLog> query(AuditQuery query) {
        int size = Math.min(Math.max(query.getSize() == null ? 20 : query.getSize(), 1), 200);
        int page = Math.max(query.getPage() == null ? 0 : query.getPage(), 0);
        Specification<AuditLog> spec = (root, cq, cb) -> {
            var predicates = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (query.getUsername() != null && !query.getUsername().isBlank()) {
                predicates.add(cb.equal(root.get("username"), query.getUsername().trim()));
            }
            if (query.getAction() != null && !query.getAction().isBlank()) {
                predicates.add(cb.equal(root.get("action"), query.getAction().trim()));
            }
            if (query.getObjectType() != null && !query.getObjectType().isBlank()) {
                predicates.add(cb.equal(root.get("objectType"), query.getObjectType().trim()));
            }
            if (query.getObjectId() != null && !query.getObjectId().isBlank()) {
                predicates.add(cb.equal(root.get("objectId"), query.getObjectId().trim()));
            }
            if (query.getDateFrom() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.getDateFrom().atStartOfDay()));
            }
            if (query.getDateTo() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), query.getDateTo().plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return auditLogRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private String currentIp() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            return IpUtil.clientIp(request);
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
