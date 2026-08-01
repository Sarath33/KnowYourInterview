package com.knowyourinterview.api.auth;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.user.UserRepository;

/**
 * The server-side half of the confirm-your-email gate: one call, in front of the actions that
 * need a reachable address.
 * <p>
 * Which actions, and why those:
 * <ul>
 *   <li><b>Creating and submitting an experience</b> — a submission leads to a payout, and an
 *       unreachable contributor is one nobody can pay or ask about their proof documents. It's
 *       also the cheapest place to stop throwaway-address content farming.</li>
 *   <li><b>Purchasing</b> — money changes hands, and a purchase with no way to contact the
 *       buyer makes any later refund or dispute unresolvable.</li>
 * </ul>
 * Browsing, reading already-unlocked content, and the whole auth surface stay open: gating
 * those would cost signups without protecting anything.
 * <p>
 * Reads the database rather than a JWT claim, deliberately. Access tokens live 15 minutes, so
 * a claim would leave a user who just confirmed still blocked until their next refresh — the
 * one moment they're most likely to immediately retry what they were stopped from doing. One
 * indexed primary-key lookup on actions this infrequent is a fair price for the gate being
 * accurate the instant it changes.
 */
@Component
public class EmailVerificationGuard {

    private final UserRepository userRepository;

    public EmailVerificationGuard(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @throws EmailNotVerifiedException if this account hasn't confirmed its address, or —
     *         defensively — if the user row has gone missing, which shouldn't happen for a
     *         caller holding a valid token but shouldn't fail open either.
     */
    @Transactional(readOnly = true)
    public void requireVerified(UUID userId, String action) {
        boolean verified = userRepository.findById(userId)
                .map(user -> user.isEmailVerified())
                .orElse(false);
        if (!verified) {
            throw new EmailNotVerifiedException(
                    "Confirm your email address before " + action + ". Check your inbox, or request a new link.");
        }
    }
}
