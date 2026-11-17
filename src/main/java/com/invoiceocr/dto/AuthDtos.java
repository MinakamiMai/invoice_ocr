package com.invoiceocr.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空") String username,
                               @NotBlank(message = "密码不能为空") String password) {
    }

    public record UserInfo(Long id, String username, String displayName, String role, String roleLabel) {
    }

    public record LoginResponse(String token, LocalDateTime expiresAt, UserInfo user) {
    }
}
