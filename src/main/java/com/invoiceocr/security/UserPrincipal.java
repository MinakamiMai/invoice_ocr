package com.invoiceocr.security;

import com.invoiceocr.domain.Role;

public record UserPrincipal(Long id, String username, String displayName, Role role) {
}
