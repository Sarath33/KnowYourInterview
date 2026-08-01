package com.knowyourinterview.api.auth;

import java.time.Duration;
import java.time.Instant;
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
 * Issuing and redeeming email-confirmation links.
 * <p>
 * Split out of AuthService rather than added to it: AuthService is already the largest thing
 * in the auth package and this is a self-contained lifecycle (issue → email → redeem) with
 * its own token table. AuthService calls {@link #issueAndSend} on registration and otherwise
 * doesn't know about any of this.
 * <p>
 * Only email/password registrations need confirming. Google signups are verified at creation
 * (see {@link User#forGoogleSignup}) because Google has already proved control of the
 * address, so they never reach this class.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailSender emailSender;
    private final Duration tokenTtl;
    private final String webBaseUrl;

    public EmailVerificationService(
            UserRepository userRepository,
            EmailVerificationTokenRepository tokenRepository,
            EmailSender emailSender,
            @Value("${app.email-verification.token-ttl-hours:24}") long tokenTtlHours,
            @Value("${app.web-base-url:http://localhost:5173}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailSender = emailSender;
        this.tokenTtl = Duration.ofHours(tokenTtlHours);
        this.webBaseUrl = webBaseUrl.endsWith("/")
                ? webBaseUrl.substring(0, webBaseUrl.length() - 1)
                : webBaseUrl;
    }

    /**
     * Issues a fresh confirmation link and emails it. Called on registration, and again for
     * every resend.
     * <p>
     * Any outstanding token for the user is invalidated first, so only the newest link works
     * — see {@link EmailVerificationTokenRepository#invalidateOutstandingTokens}. Silently
     * does nothing for an already-verified account: there's nothing to confirm, and issuing a
     * live token for a verified address would be handing out a credential nobody asked for.
     */
    @Transactional
    public void issueAndSend(User user) {
        if (user.isEmailVerified()) {
            return;
        }
        Instant now = Instant.now();
        tokenRepository.invalidateOutstandingTokens(user.getId(), now);

        String rawToken = SecureTokens.generate();
        tokenRepository.save(new EmailVerificationToken(
                UUID.randomUUID(),
                user.getId(),
                SecureTokens.sha256Hex(rawToken),
                now.plus(tokenTtl)));

        AuthEmails.Message message = AuthEmails.confirmEmail(
                user.getDisplayName(), webBaseUrl + "/confirm-email?token=" + rawToken);
        emailSender.send(user.getEmail(), message.subject(), message.htmlBody(), message.textBody());
    }

    /**
     * Redeems a confirmation link.
     * <p>
     * Unlike most of the auth surface this is intentionally specific in its errors — a user
     * staring at a dead link needs to know whether to request a new one, and there's no
     * enumeration risk in saying so, since holding the token already implies holding the
     * inbox. An unknown, expired or reused token is an {@link InvalidTokenException}; a
     * token whose user is already verified succeeds quietly, so double-clicking the link in
     * an email client that prefetches URLs doesn't show an error.
     */
    @Transactional
    public void verify(String rawToken) {
        EmailVerificationToken token = tokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawToken))
                .orElseThrow(() -> new InvalidTokenException(
                        "That confirmation link isn't valid. Request a new one from the app."));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new InvalidTokenException("That confirmation link isn't valid."));

        // Checked before the used/expired guards: a user who already confirmed and clicked
        // the link again (or whose mail client prefetched it) should see success, not an
        // error about a token they no longer need.
        if (user.isEmailVerified()) {
            return;
        }
        if (token.isUsed()) {
            throw new InvalidTokenException(
                    "That confirmation link has already been used. Request a new one from the app.");
        }
        if (token.isExpired()) {
            throw new InvalidTokenException(
                    "That confirmation link has expired. Request a new one from the app.");
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
     * same no-enumeration posture as forgot-password. An unknown address, or one that's
     * already verified, does nothing at all; the response is identical either way. Abuse is
     * held back by the rate limiter (see RateLimitingFilter), which is tighter on this path
     * than on login because the cost of getting it wrong is mail sent to a third party.
     */
    @Transactional
    public void resend(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(this::issueAndSend);
    }
}
