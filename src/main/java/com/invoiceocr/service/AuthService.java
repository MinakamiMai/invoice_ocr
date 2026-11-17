package com.invoiceocr.service;

import com.invoiceocr.domain.AppUser;
import com.invoiceocr.dto.AuthDtos;
import com.invoiceocr.repo.AppUserRepository;
import com.invoiceocr.security.JwtService;
import com.invoiceocr.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.invoiceocr.web.ApiException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request, HttpServletRequest httpRequest) {
        AppUser user = userRepository.findByUsername(request.username().trim()).orElse(null);
        if (user == null || !user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditService.logAs(null, request.username(), null, "LOGIN_FAILURE", "USER",
                    request.username(), com.invoiceocr.util.IpUtil.clientIp(httpRequest), "登录失败");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "用户名或密码错误");
        }
        JwtService.IssuedToken issued = jwtService.issue(user);
        auditService.logAs(user.getId(), user.getUsername(), user.getRole(), "LOGIN_SUCCESS", "USER",
                user.getUsername(), com.invoiceocr.util.IpUtil.clientIp(httpRequest), "登录成功");
        return new AuthDtos.LoginResponse(issued.token(), issued.expiresAt(), toUserInfo(user));
    }

    public AuthDtos.UserInfo me(UserPrincipal principal) {
        AppUser user = userRepository.findById(principal.id())
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        return toUserInfo(user);
    }

    private AuthDtos.UserInfo toUserInfo(AppUser user) {
        return new AuthDtos.UserInfo(user.getId(), user.getUsername(), user.getDisplayName(),
                user.getRole().name(), user.getRole().getLabel());
    }
}
