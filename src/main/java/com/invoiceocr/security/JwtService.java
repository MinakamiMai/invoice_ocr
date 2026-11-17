package com.invoiceocr.security;

import com.invoiceocr.config.AppProperties;
import com.invoiceocr.domain.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlMinutes;

    public JwtService(AppProperties properties) {
        String secret = properties.getSecurity().getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.security.jwt.secret must contain at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = properties.getSecurity().getJwt().getTtlMinutes();
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(ttlMinutes * 60);
        String token = Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
        LocalDateTime expiresAt = LocalDateTime.ofInstant(expiry, ZoneId.systemDefault());
        return new IssuedToken(token, expiresAt);
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public record IssuedToken(String token, LocalDateTime expiresAt) {
    }
}
