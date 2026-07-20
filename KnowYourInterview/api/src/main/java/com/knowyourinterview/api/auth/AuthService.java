package com.knowyourinterview.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.auth.dto.UserResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.user.PasswordResetToken;
import com.knowyourinterview.api.user.PasswordResetTokenRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String REFRESH_KEY_PREFIX = "refresh:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final Duration passwordResetTtl;

    public AuthService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            StringRedisTemplate redisTemplate,
            @Value("${app.password-reset.token-ttl-minutes}") long passwordResetTtlMinutes) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.passwordResetTtl = Duration.ofMinutes(passwordResetTtlMinutes);
    }

    @Transactional
    public AuthResponse register(String email, String rawPassword, String displayName) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword), displayName);
        userRepository.save(user);
        return issueTokens(user);
    }

    public AuthResponse login(String email, String rawPassword) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    public AuthResponse refresh(String refreshToken) {
        JwtService.RefreshClaims claims;
        try {
            claims = jwtService.parseRefreshToken(refreshToken);
        } catch (RuntimeException e) {
            throw new InvalidTokenException("Refresh token is invalid or expired");
        }

        String redisKey = REFRESH_KEY_PREFIX + claims.jti();
        String storedUserId = redisTemplate.opsForValue().get(redisKey);
        if (storedUserId == null || !storedUserId.equals(claims.userId().toString())) {
            throw new InvalidTokenException("Refresh token has already been used or was revoked");
        }
        // Rotate: the old refresh token is single-use.
        redisTemplate.delete(redisKey);

        User user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new InvalidTokenException("Account no longer exists"));
        return issueTokens(user);
    }

    public void logout(String refreshToken) {
        try {
            JwtService.RefreshClaims claims = jwtService.parseRefreshToken(refreshToken);
            redisTemplate.delete(REFRESH_KEY_PREFIX + claims.jti());
        } catch (RuntimeException e) {
            // Already invalid/expired — nothing to revoke. Logout is idempotent either way.
            log.debug("Logout called with an unparseable refresh token; treating as already logged out");
        }
    }

    /**
     * Always succeeds from the caller's perspective (no user-enumeration signal).
     * Email delivery isn't wired up yet, so the reset link is logged instead —
     * swap this for a real email send once a provider (Postmark/SendGrid) is set up.
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            String rawToken = generateRawToken();
            String tokenHash = sha256Hex(rawToken);
            PasswordResetToken resetToken = new PasswordResetToken(
                    UUID.randomUUID(), user.getId(), tokenHash, Instant.now().plus(passwordResetTtl));
            passwordResetTokenRepository.save(resetToken);

            // STUB: log instead of emailing. In the web app, this becomes a link like
            // http://localhost:5173/reset-password?token=<rawToken>
            log.info("Password reset requested for {}. Reset token (would be emailed): {}", email, rawToken);
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = sha256Hex(rawToken);
        PasswordResetToken resetToken = passwordResetTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset token"));

        if (resetToken.isUsed() || resetToken.isExpired()) {
            throw new InvalidTokenException("Invalid or expired reset token");
        }

        User user = userRepository.findById(resetToken.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Invalid or expired reset token"));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.markUsed();
        passwordResetTokenRepository.save(resetToken);
    }

    private AuthResponse issueTokens(User user) {
        JwtService.AccessToken access = jwtService.issueAccessToken(user.getId(), user.getEmail(), user.isAdmin());
        JwtService.RefreshToken refresh = jwtService.issueRefreshToken(user.getId());

        redisTemplate.opsForValue().set(
                REFRESH_KEY_PREFIX + refresh.jti(),
                user.getId().toString(),
                jwtService.refreshTtl().toSeconds(),
                TimeUnit.SECONDS);

        return new AuthResponse(access.token(), refresh.token(), UserResponse.from(user));
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
