package com.knowyourinterview.api.functional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.auth.AuthService;
import com.knowyourinterview.api.functional.support.FunctionalTestBase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-PWD — the password reset cycle end to end, against real rows. See
 * {@code docs/09-test-plan.md} §7.2.
 *
 * <h2>Why this test reads the application log</h2>
 * There is no email provider yet, so {@code AuthService#forgotPassword} logs the reset link
 * instead of sending it, and the raw token is never persisted (only its SHA-256 hash is). The
 * log line is therefore the <em>actual</em> delivery channel today — an operator reads it out and
 * hands it to the user — so capturing it is not a hack around the design, it is the only way to
 * test the flow a user really goes through, and it doubles as a check that the link is
 * well-formed and complete. When a real provider is wired up, this capture is the one thing here
 * that needs replacing (with a fake mailer); every assertion below stays valid.
 */
class PasswordResetFunctionalIT extends FunctionalTestBase {

    private static final Pattern RESET_LINK =
            Pattern.compile("(https?://\\S+)/reset-password\\?token=([A-Za-z0-9_-]+)");

    private ListAppender<ILoggingEvent> logCapture;
    private Logger authServiceLogger;

    @BeforeEach
    void captureAuthServiceLog() {
        authServiceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        authServiceLogger.addAppender(logCapture);
        authServiceLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseAuthServiceLog() {
        if (authServiceLogger != null && logCapture != null) {
            authServiceLogger.detachAppender(logCapture);
            logCapture.stop();
        }
    }

    @Test
    @DisplayName("FT-PWD-01/02: a full reset cycle works and retires the old password")
    void fullResetCycle() {
        Actor actor = registerUser();

        ResponseEntity<String> forgot = post("/api/v1/auth/forgot-password", null,
                new JSONObject().put("email", actor.email()));
        assertThat(statusOf(forgot)).isEqualTo(200);

        String rawToken = capturedResetToken();
        ResponseEntity<String> reset = post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "a-brand-new-password"));
        assertThat(statusOf(reset)).isEqualTo(200);
        assertThat(messageOf(reset)).isEqualTo("Password updated.");

        // FT-PWD-01: the new password works.
        assertThat(statusOf(post("/api/v1/auth/login", null, loginBody(actor.email(), "a-brand-new-password"))))
                .isEqualTo(200);
        // FT-PWD-02: the old one doesn't.
        assertThat(statusOf(post("/api/v1/auth/login", null, loginBody(actor.email(), PASSWORD))))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("FT-PWD-03: a reset token is single-use")
    void resetTokenIsSingleUse() {
        Actor actor = registerUser();
        post("/api/v1/auth/forgot-password", null, new JSONObject().put("email", actor.email()));
        String rawToken = capturedResetToken();

        assertThat(statusOf(post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "first-new-password"))))
                .isEqualTo(200);

        ResponseEntity<String> replay = post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "attacker-chosen-password"));

        assertThat(statusOf(replay)).isEqualTo(401);
        assertThat(messageOf(replay)).isEqualTo("Invalid or expired reset token");
        // The password the legitimate user set must still be the live one.
        assertThat(statusOf(post("/api/v1/auth/login", null,
                loginBody(actor.email(), "attacker-chosen-password")))).isEqualTo(401);
        assertThat(statusOf(post("/api/v1/auth/login", null,
                loginBody(actor.email(), "first-new-password")))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-PWD-04: an expired reset token is rejected")
    void expiredResetTokenIsRejected() {
        Actor actor = registerUser();
        post("/api/v1/auth/forgot-password", null, new JSONObject().put("email", actor.email()));
        String rawToken = capturedResetToken();

        // Age the row past its TTL rather than sleeping through app.password-reset.token-ttl-minutes.
        jdbc.update("UPDATE password_reset_tokens SET expires_at = ? WHERE user_id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(60)), actor.id());

        ResponseEntity<String> response = post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "too-late-password"));

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(statusOf(post("/api/v1/auth/login", null, loginBody(actor.email(), PASSWORD)))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-PWD-05: an unknown reset token is rejected")
    void unknownResetTokenIsRejected() {
        registerUser();

        ResponseEntity<String> response = post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", "a-token-nobody-ever-issued").put("newPassword", "whatever-password"));

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(messageOf(response)).isEqualTo("Invalid or expired reset token");
    }

    @Test
    @DisplayName("FT-PWD-06: the token is stored hashed, never raw")
    void resetTokenIsStoredHashed() throws Exception {
        Actor actor = registerUser();
        post("/api/v1/auth/forgot-password", null, new JSONObject().put("email", actor.email()));
        String rawToken = capturedResetToken();

        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM password_reset_tokens WHERE user_id = ?", String.class, actor.id());

        assertThat(storedHash).isNotNull();
        assertThat(storedHash).isNotEqualTo(rawToken);
        assertThat(storedHash).hasSize(64).matches("[0-9a-f]{64}");
        // Anyone with read access to the table still can't mint a working reset link.
        assertThat(storedHash).isEqualTo(sha256Hex(rawToken));
    }

    @Test
    @DisplayName("FT-PWD-07: forgot-password does not reveal whether an account exists")
    void forgotPasswordDoesNotEnumerateAccounts() {
        Actor known = registerUser();

        ResponseEntity<String> forKnown = post("/api/v1/auth/forgot-password", null,
                new JSONObject().put("email", known.email()));
        ResponseEntity<String> forUnknown = post("/api/v1/auth/forgot-password", null,
                new JSONObject().put("email", uniqueEmail("nobody-here")));

        assertThat(statusOf(forKnown)).isEqualTo(200);
        assertThat(statusOf(forUnknown)).isEqualTo(200);
        assertThat(messageOf(forUnknown)).isEqualTo(messageOf(forKnown));
        // ...and no token row was created for the address that doesn't exist.
        assertThat(countRows("password_reset_tokens")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-PWD-08: resetting a password does NOT revoke existing sessions (gap G4)")
    void resetDoesNotRevokeExistingSessions() {
        Actor actor = registerUser();
        post("/api/v1/auth/forgot-password", null, new JSONObject().put("email", actor.email()));
        String rawToken = capturedResetToken();

        post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "rotated-after-compromise"));

        ResponseEntity<String> refreshAfterReset = post("/api/v1/auth/refresh", null,
                new JSONObject().put("refreshToken", actor.refreshToken()));

        // Pinning current behaviour, not endorsing it. A user resetting their password is often
        // doing so *because* they think someone else has access; that other session surviving is
        // a product decision worth revisiting. See docs/09-test-plan.md gap G4 — if this starts
        // failing because sessions are now revoked, the fix is to update this test, not the code.
        assertThat(statusOf(refreshAfterReset))
                .as("if this is now 401, session revocation on reset was implemented — update G4")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("FT-PWD-09: reset-password validates the new password's length")
    void resetPasswordValidatesNewPassword() {
        Actor actor = registerUser();
        post("/api/v1/auth/forgot-password", null, new JSONObject().put("email", actor.email()));
        String rawToken = capturedResetToken();

        ResponseEntity<String> response = post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "short"));

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet()).contains("newPassword");
        // The token must survive a rejected attempt — otherwise a typo burns the user's one link.
        assertThat(statusOf(post("/api/v1/auth/reset-password", null,
                new JSONObject().put("token", rawToken).put("newPassword", "a-long-enough-password"))))
                .isEqualTo(200);
    }

    /**
     * Pulls the single reset link out of what {@code AuthService} logged, and asserts along the
     * way that the link is complete and absolute — a bare token, or one built against the wrong
     * base URL, would be useless to the operator who has to forward it.
     */
    private String capturedResetToken() {
        List<ILoggingEvent> events = List.copyOf(logCapture.list);
        String matched = null;
        for (ILoggingEvent event : events) {
            Matcher matcher = RESET_LINK.matcher(event.getFormattedMessage());
            if (matcher.find()) {
                assertThat(matcher.group(1))
                        .as("reset link should point at the configured web base URL")
                        .isEqualTo("http://localhost:5173");
                matched = matcher.group(2);
            }
        }
        assertThat(matched)
                .as("expected AuthService to log a reset link; captured: %s", events)
                .isNotNull();
        return matched;
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
