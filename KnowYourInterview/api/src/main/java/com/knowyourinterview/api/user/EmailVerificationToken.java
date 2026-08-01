package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use, expiring token proving control of an email address, handed out at
 * registration and redeemable once via POST /api/v1/auth/verify-email.
 * <p>
 * Deliberately a near-copy of {@link PasswordResetToken} rather than a shared base class or
 * a single table with a "purpose" column: the two have the same shape today but no reason to
 * stay identical (their lifetimes already differ — hours vs. one hour — and a reset token is
 * far more dangerous if leaked, so they'll likely diverge on things like invalidate-on-use
 * scope). Coupling them would make the next difference awkward rather than making today
 * simpler.
 * <p>
 * Only the SHA-256 hash of the raw token is stored, same as password reset — the raw value
 * exists only in the emailed link, so a database leak doesn't hand anyone a working
 * confirmation link.
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

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

    public void markUsed() {
        this.usedAt = Instant.now();
    }
}
