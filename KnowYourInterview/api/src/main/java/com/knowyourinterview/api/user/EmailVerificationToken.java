package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use, expiring 6-digit code proving control of an email address, emailed at
 * registration and redeemed via POST /api/v1/auth/verify-email.
 * <p>
 * Deliberately not sharing a base class or table with {@link PasswordResetToken}, and the gap
 * between them is now the argument for that: a reset token is 256 random bits in a link and
 * lives an hour; this is six digits typed by hand and lives ten minutes, with a guess counter
 * a reset token has no use for. Coupling them would have made this divergence awkward.
 * <p>
 * The code is stored as a SHA-256 hash for consistency with the reset token, but see
 * {@code SecureTokens}' class Javadoc: hashing six digits is not meaningful protection, since
 * a million candidates can be exhausted instantly. The real defences are the short expiry and
 * {@link #MAX_ATTEMPTS}.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

    /**
     * Wrong guesses allowed before the code is burned and the user has to request a new one.
     * <p>
     * Five is the usual figure, and the arithmetic supports it: against a million possibilities
     * it gives an attacker a 1-in-200,000 chance per code, and the ten-minute expiry caps how
     * many codes they can even work through. Low enough to be safe, high enough that someone
     * fat-fingering a digit twice isn't sent back to their inbox.
     */
    public static final short MAX_ATTEMPTS = 5;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Wrong guesses so far. Counted against the row rather than per-IP, because rotating IPs
     * would otherwise hand an attacker a fresh budget each time. */
    @Column(nullable = false)
    private short attempts;

    protected EmailVerificationToken() {
        // JPA
    }

    public EmailVerificationToken(UUID id, UUID userId, String tokenHash, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public short getAttempts() {
        return attempts;
    }

    /** True once the guess budget is gone. The caller burns the code at that point — leaving it
     * alive but permanently rejecting would be indistinguishable from a wrong code to the user
     * and would need the same handling anyway. */
    public boolean isOutOfAttempts() {
        return attempts >= MAX_ATTEMPTS;
    }

    public void recordFailedAttempt() {
        this.attempts++;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }
}
