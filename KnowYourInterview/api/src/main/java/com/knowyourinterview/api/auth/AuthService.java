package com.knowyourinterview.api.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
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
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.common.SecureTokens;
import com.knowyourinterview.api.email.AuthEmails;
import com.knowyourinterview.api.email.EmailSender;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.user.PasswordResetToken;
import com.knowyourinterview.api.user.PasswordResetTokenRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String REFRESH_KEY_PREFIX = "refresh:";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final StringRedisTemplate redisTemplate;
    private final Duration passwordResetTtl;
    private final GoogleIdTokenVerifierPort googleIdTokenVerifierPort;
    private final EmailVerificationService emailVerificationService;
    private final EmailSender emailSender;
    private final String adminBootstrapSecret;
    private final String webBaseUrl;

    public AuthService(
            UserRepository userRepository,
            PasswordResetTokenRepository passwordResetTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            StringRedisTemplate redisTemplate,
            @Value("${app.password-reset.token-ttl-minutes}") long passwordResetTtlMinutes,
            GoogleIdTokenVerifierPort googleIdTokenVerifierPort,
            EmailVerificationService emailVerificationService,
            EmailSender emailSender,
            @Value("${app.admin-bootstrap.secret:}") String adminBootstrapSecret,
            @Value("${app.web-base-url:http://localhost:5173}") String webBaseUrl) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.redisTemplate = redisTemplate;
        this.passwordResetTtl = Duration.ofMinutes(passwordResetTtlMinutes);
        this.googleIdTokenVerifierPort = googleIdTokenVerifierPort;
        this.emailVerificationService = emailVerificationService;
        this.emailSender = emailSender;
        this.adminBootstrapSecret = adminBootstrapSecret;
        // Trailing slash trimmed so the link built in forgotPassword can always append
        // "/reset-password?token=…" without producing a doubled slash.
        this.webBaseUrl = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
    }

    /**
     * Registration issues a session immediately, unverified. Confirmation gates what the
     * account can *do* (submitting, purchasing — see EmailVerificationGuard), not whether it
     * can log in: bouncing someone to a "check your email" dead end is the single easiest way
     * to lose them, and browsing costs nothing to allow.
     * <p>
     * The confirmation email is sent inside the same transaction as the insert, deliberately.
     * EmailSender never throws (see its contract), so a mail failure can't roll the
     * registration back — and the ordering means a committed account always has a token row
     * committed with it, rather than a window where the user exists but has nothing to click.
     */
    @Transactional
    public AuthResponse register(String email, String rawPassword, String displayName) {
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyRegisteredException(email);
        }
        User user = new User(UUID.randomUUID(), email, passwordEncoder.encode(rawPassword), displayName);
        userRepository.save(user);
        emailVerificationService.issueAndSend(user);
        return issueTokens(user);
    }

    public AuthResponse login(String email, String rawPassword) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(InvalidCredentialsException::new);
        // Google-only accounts have no password_hash — reject rather than NPE on matches().
        if (user.getPasswordHash() == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return issueTokens(user);
    }

    /**
     * Handles both signup and login for "Sign in with Google" in one call, mirroring how
     * Google Identity Services itself doesn't distinguish the two. Resolution order:
     * 1) an account already linked to this Google subject id — plain login;
     * 2) an existing email/password account with the same verified email — link it, so a
     *    user who registered normally can start using Google without ending up with two
     *    accounts;
     * 3) neither — create a new, password-less account.
     */
    @Transactional
    public AuthResponse googleLogin(String idTokenString) {
        GoogleUserInfo googleUser = googleIdTokenVerifierPort.verify(idTokenString);

        User user = userRepository.findByGoogleSub(googleUser.subject()).orElse(null);
        if (user == null) {
            user = userRepository.findByEmailIgnoreCase(googleUser.email()).orElse(null);
            if (user != null) {
                user.linkGoogleSub(googleUser.subject());
            } else {
                user = User.forGoogleSignup(
                        UUID.randomUUID(), googleUser.email(), googleUser.name(), googleUser.subject());
            }
            userRepository.save(user);
        }

        return issueTokens(user);
    }

    /**
     * Promotes an already-registered account to admin, gated by ADMIN_BOOTSTRAP_SECRET
     * rather than an existing admin's JWT — there's a chicken-and-egg problem the first
     * time this runs on a fresh environment, since no admin exists yet to authorize one.
     * Blank/unset secret disables the endpoint entirely (same graceful-degradation pattern
     * as Google/Razorpay/Sentry). Doesn't create the account — register (or Google
     * Sign-In) first, then call this once to flip the flag. Not the only way to get an
     * admin (a direct DB update still works), just the one that doesn't need psql access.
     */
    @Transactional
    public void bootstrapAdmin(String email, String providedSecret) {
        if (adminBootstrapSecret == null || adminBootstrapSecret.isBlank()) {
            throw new AdminBootstrapNotConfiguredException();
        }
        // Constant-time comparison — this secret is the only thing standing between a
        // request and creating an admin account, so it shouldn't be distinguishable via
        // response-time differences the way a naive String.equals early-exit would allow.
        boolean matches = MessageDigest.isEqual(
                providedSecret.getBytes(StandardCharsets.UTF_8),
                adminBootstrapSecret.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new InvalidTokenException("Invalid bootstrap secret");
        }
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException(
                        "No account found for that email — register (or sign in with Google) first"));
        user.promoteToAdmin();
        userRepository.save(user);
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
     * Always succeeds from the caller's perspective, whether or not the address is
     * registered — the response must not become a user-enumeration oracle. That's also why
     * the send happens through EmailSender, which swallows delivery failures: a provider
     * outage must not turn into a different response for a real address than a fake one.
     * <p>
     * No longer a stub. With SMTP configured this genuinely emails the link; with it unset
     * (local dev) LoggingEmailSender writes the message to the console, which is the same
     * behaviour this method used to have hard-coded.
     */
    @Transactional
    public void forgotPassword(String email) {
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            String rawToken = SecureTokens.generate();
            PasswordResetToken resetToken = new PasswordResetToken(
                    UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(rawToken),
                    Instant.now().plus(passwordResetTtl));
            passwordResetTokenRepository.save(resetToken);

            AuthEmails.Message message = AuthEmails.passwordReset(
                    user.getDisplayName(), webBaseUrl + "/reset-password?token=" + rawToken);
            emailSender.send(user.getEmail(), message.subject(), message.htmlBody(), message.textBody());
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        String tokenHash = SecureTokens.sha256Hex(rawToken);
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

}
