package com.invoiceocr.security;

import com.invoiceocr.domain.Role;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    public UserPrincipal require() {
        UserPrincipal principal = getOrNull();
        if (principal == null) {
            throw new IllegalStateException("No authenticated user in context");
        }
        return principal;
    }

    public UserPrincipal getOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof UserPrincipal principal) {
            return principal;
        }
        return null;
    }

    public boolean isPrivileged() {
        UserPrincipal principal = require();
        return principal.role() == Role.ADMIN
                || principal.role() == Role.REVIEWER
                || principal.role() == Role.AUDITOR;
    }
}
