package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    /**
     * Burns every outstanding token for a user, called right before a new one is issued (see
     * EmailVerificationService#issueAndSend).
     * <p>
     * Without this, every "resend" would leave its predecessor live, so a user who requested
     * five links would have five working confirmations floating around in their inbox —
     * each one a credential, and the older ones sitting in mailboxes long after the user has
     * stopped thinking about them. Only the most recent link should work, which is also what
     * people expect from a resend.
     * <p>
     * Marks used rather than deleting, so the rows stay as an audit trail of how many
     * attempts an address needed.
     */
    @Modifying
    @Query("""
            UPDATE EmailVerificationToken t
            SET t.usedAt = :now
            WHERE t.userId = :userId AND t.usedAt IS NULL
            """)
    int invalidateOutstandingTokens(@Param("userId") UUID userId, @Param("now") Instant now);
}
