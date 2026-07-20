package com.knowyourinterview.api.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and parses the two JWT types this app uses:
 *  - "access" tokens: short-lived, sent as Authorization: Bearer <token> on every request.
 *  - "refresh" tokens: longer-lived, carry a random jti that AuthService tracks in Redis
 *    so refresh/logout can actually revoke them (a bare JWT can't be revoked on its own).
 *
 * This class has no DB/Redis dependency — it's pure token math — so it's safe to use
 * in slice tests without spinning up infrastructure.
 */
@Service
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ADMIN = "admin";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final Key key;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl-minutes}") long accessTtlMinutes,
            @Value("${app.jwt.refresh-token-ttl-days}") long refreshTtlDays) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(accessTtlMinutes);
        this.refreshTtl = Duration.ofDays(refreshTtlDays);
    }

    public record AccessToken(String token, Instant expiresAt) {}

    public record RefreshToken(String token, String jti, Instant expiresAt) {}

    public AccessToken issueAccessToken(UUID userId, String email, boolean admin) {
        Instant now = Instant.now();
        Instant exp = now.plus(accessTtl);
        String token = Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_ADMIN, admin)
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new AccessToken(token, exp);
    }

    public RefreshToken issueRefreshToken(UUID userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(refreshTtl);
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(userId.toString())
                .id(jti)
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new RefreshToken(token, jti, exp);
    }

    public Duration refreshTtl() {
        return refreshTtl;
    }

    /** Parsed, verified access-token claims. Throws JwtException/IllegalArgumentException if invalid. */
    public record AccessClaims(UUID userId, String email, boolean admin) {}

    public AccessClaims parseAccessToken(String token) {
        Claims claims = parse(token);
        requireType(claims, TYPE_ACCESS);
        return new AccessClaims(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_EMAIL, String.class),
                Boolean.TRUE.equals(claims.get(CLAIM_ADMIN, Boolean.class)));
    }

    public record RefreshClaims(UUID userId, String jti) {}

    public RefreshClaims parseRefreshToken(String token) {
        Claims claims = parse(token);
        requireType(claims, TYPE_REFRESH);
        return new RefreshClaims(UUID.fromString(claims.getSubject()), claims.getId());
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith((javax.crypto.SecretKey) key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private void requireType(Claims claims, String expected) {
        Object typ = claims.get(CLAIM_TYPE);
        if (!expected.equals(typ)) {
            throw new JwtException("Unexpected token type: " + typ);
        }
    }
}
