package com.knowyourinterview.api.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    /**
     * The one live code for a user, if there is one.
     * <p>
     * Deliberately looked up by <em>user</em> and not by code hash — the inverse of how the
     * password-reset token is found. A 256-bit token is unique enough to be an identifier in its
     * own right; a six-digit code is not. Searching by hash alone would mean any code that
     * happened to collide with some other account's live code would resolve to that account, so
     * a few thousand guesses against no particular target would eventually land. Scoping to the
     * user first is what makes {@link EmailVerificationToken#MAX_ATTEMPTS} meaningful: every
     * guess is spent against one specific account's budget.
     * <p>
     * "Ordered by newest" is belt-and-braces — {@code invalidateOutstandingTokens} already
     * ensures at most one row is unused per user — but it means a stray extra row from a
     * concurrent resend can't resurrect a superseded code.
     */
    Optional<EmailVerificationToken> findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(UUID userId);

    /**
     * Burns every outstanding code for a user, called right before a new one is issued (see
     * EmailVerificationService#issueAndSend).
     * <p>
     * Without this, a resend would leave its predecessor live, so a user who requested five
     * codes would have five working ones — and, worse for a short code, five separate attempt
     * budgets to guess against. Only the most recent should work, which is also what people
     * expect from a resend.
     * <p>
     * Marks used rather than deleting, so the rows stay as a record of how many attempts an
     * address needed.
     */
    @Modifying
    @Query("""
            UPDATE EmailVerificationToken t
            SET t.usedAt = :now
            WHERE t.userId = :userId AND t.usedAt IS NULL
            """)
    int invalidateOutstandingTokens(@Param("userId") UUID userId, @Param("now") Instant now);
}
