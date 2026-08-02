package com.knowyourinterview.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.SecureTokens;
import com.knowyourinterview.api.email.AuthEmails;
import com.knowyourinterview.api.email.EmailSender;
import com.knowyourinterview.api.user.EmailVerificationToken;
import com.knowyourinterview.api.user.EmailVerificationTokenRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

/**
 * Issuing and checking the 6-digit confirmation code.
 * <p>
 * Split out of AuthService rather than added to it: AuthService is already the largest thing in
 * the auth package and this is a self-contained lifecycle (issue → email → redeem) with its own
 * table. AuthService calls {@link #issueAndSend} on registration and otherwise doesn't know
 * about any of this.
 * <p>
 * Only email/password registrations need confirming. Google signups are verified at creation
 * (see {@link User#forGoogleSignup}) because Google has already proved control of the address,
 * so they never reach this class.
 *
 * <h2>Why a short code is safe here</h2>
 * Six digits is a million possibilities, which is trivially brute-forceable given unlimited
 * tries. Three things together make it safe, and removing any one of them breaks it:
 * <ol>
 *   <li>a ten-minute expiry, so there's a small window to work in;</li>
 *   <li>a hard cap of {@link EmailVerificationToken#MAX_ATTEMPTS} wrong guesses <em>per code
 *       row</em> — not per IP, which an attacker can rotate freely;</li>
 *   <li>a rate limit on the endpoint itself (see RateLimitingFilter), which bounds how fast
 *       anyone can burn through codes by repeatedly resending.</li>
 * </ol>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final int CODE_DIGITS = 6;

    /** Deliberately identical for every failure mode below. See {@link #verify}. */
    private static final String REJECTION_MESSAGE =
            "That code isn't valid or has expired. Request a new one and try again.";

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailSender emailSender;
    private final Duration codeTtl;

    public EmailVerificationService(
            UserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            EmailSender emailSender,
            @Value("${app.email-verification.code-ttl-minutes:10}") long codeTtlMinutes) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailSender = emailSender;
        this.codeTtl = Duration.ofMinutes(codeTtlMinutes);
    }

    /**
     * Issues a fresh code and emails it. Called on registration, and again for every resend.
     * <p>
     * Any outstanding code for the user is invalidated first — see
     * {@link EmailVerificationTokenRepository#invalidateOutstandingTokens}, which matters more
     * for a short code than it would for a link, since each live code carries its own guess
     * budget. Silently does nothing for an already-confirmed account: there's nothing to
     * confirm, and mailing a live code to a confirmed address would be handing out a credential
     * nobody asked for.
     */
    @Transactional
    public void issueAndSend(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        Instant now = Instant.now();
        tokenRepository.invalidateOutstandingTokens(user.getId(), now);

        String code = SecureTokens.numericCode(CODE_DIGITS);
        tokenRepository.save(new EmailVerificationToken(
                UUID.randomUUID(),
                user.getId(),
                SecureTokens.sha256Hex(code),
                now.plus(codeTtl)));

        AuthEmails.Message message =
                AuthEmails.confirmEmail(user.getDisplayName(), code, codeTtl.toMinutes());
        emailSender.send(user.getEmail(), message.subject(), message.htmlBody(), message.textBody());
    }

    /**
     * Checks a code against the live one for that address and confirms the account on a match.
     * <p>
     * Addressed by email as well as code because a six-digit code is not an identifier — see
     * {@link EmailVerificationTokenRepository#findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc}
     * for why looking it up by value alone would be a real weakness rather than a convenience.
     * <p>
     * <b>Every failure returns the same message.</b> Unknown address, no code outstanding,
     * expired, out of attempts, or simply wrong — the caller can't tell them apart. Being
     * specific would turn this endpoint into an oracle for which addresses are registered and
     * which have a code in flight, and the user's next step is the same in every case anyway:
     * request a new code. The distinctions are logged server-side, where they're useful for
     * support without being useful to an attacker.
     * <p>
     * An already-confirmed account succeeds rather than erroring: someone who confirmed on
     * another device and retypes the code has done nothing wrong.
     */
    @Transactional
    public void verify(String email, String code) {
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            log.debug("Email confirmation attempted for an unknown address");
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }
        if (user.isEmailVerified()) {
            return;
        }

        Optional<EmailVerificationToken> outstanding =
                tokenRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (outstanding.isEmpty()) {
            log.debug("Email confirmation attempted for user {} with no code outstanding", user.getId());
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }
        EmailVerificationToken token = outstanding.get();

        if (token.isExpired()) {
            log.debug("Email confirmation code for user {} had expired", user.getId());
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }
        if (token.isOutOfAttempts()) {
            // Shouldn't normally be reachable — the branch below burns the code on the last
            // wrong guess — but a row could reach the cap and survive if a save were ever
            // reordered, and failing closed costs nothing.
            log.warn("Email confirmation code for user {} was already out of attempts", user.getId());
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }

        if (!matches(code, token.getTokenHash())) {
            token.recordFailedAttempt();
            if (token.isOutOfAttempts()) {
                // Burn it: leaving a code alive that can never succeed just wastes the user's
                // time on retries that were always going to fail.
                token.markUsed();
                log.info("Email confirmation code for user {} burned after {} wrong attempts",
                        user.getId(), EmailVerificationToken.MAX_ATTEMPTS);
            }
            tokenRepository.save(token);
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }

        token.markUsed();
        tokenRepository.save(token);
        user.markEmailVerified();
        userRepository.save(user);
        log.info("Email verified for user {}", user.getId());
    }

    /**
     * Resend, addressed by email rather than by session.
     * <p>
     * Always succeeds from the caller's point of view, whatever the address turns out to be —
     * same no-enumeration posture as forgot-password. An unknown address, or one that's already
     * confirmed, does nothing at all. Abuse is held back by the rate limiter, which is tighter
     * on this path than on login because the cost of getting it wrong is mail sent to a third
     * party — and because each resend hands out a fresh attempt budget.
     */
    @Transactional
    public void resend(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(this::issueAndSend);
    }

    /**
     * Constant-time comparison of the submitted code's hash against the stored one.
     * <p>
     * The timing signal on a six-digit code is marginal — an attacker has five guesses, not the
     * millions a timing oracle would need to be worth exploiting. Done properly regardless
     * because it costs one method call, and because a naive {@code equals} here is exactly the
     * kind of thing that gets copied into somewhere it does matter.
     */
    private static boolean matches(String submittedCode, String storedHash) {
        if (submittedCode == null) {
            return false;
        }
        return MessageDigest.isEqual(
                SecureTokens.sha256Hex(submittedCode.trim()).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
