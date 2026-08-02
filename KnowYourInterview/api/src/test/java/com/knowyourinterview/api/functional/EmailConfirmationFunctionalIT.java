package com.knowyourinterview.api.functional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.email.LoggingEmailSender;
import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;
import com.knowyourinterview.api.functional.support.StubGoogleIdTokenVerifier;
import com.knowyourinterview.api.user.EmailVerificationToken;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-CONF — the 6-digit registration confirmation code: issuing it, checking it, the guess
 * limit, and the gate it lifts. See {@code docs/09-test-plan.md} §7.14.
 *
 * <p>Reads the sent message from {@link LoggingEmailSender} for the same reason
 * {@code PasswordResetFunctionalIT} does: the code is stored hashed, so the delivered message
 * is the only place its plaintext exists. With no SMTP host configured — pinned for this suite
 * — that logger is the genuine delivery channel, so nothing is faked to make the test work.
 *
 * <p><b>Why the attempt-limit cases are the important ones.</b> A million possibilities is
 * nothing to a script. The cap on wrong guesses is the only thing making a code this short
 * acceptable, and it's exactly the sort of control that can rot without any happy path
 * noticing. The gate cases matter for the opposite reason: too strict and a new signup can't
 * browse, or gets bounced to /login by a 401.
 */
class EmailConfirmationFunctionalIT extends FunctionalTestBase {

    /** The code as it appears in the plain-text body, indented on its own line. */
    private static final Pattern CODE_IN_EMAIL = Pattern.compile("^\\s+(\\d{6})\\s*$", Pattern.MULTILINE);

    private ListAppender<ILoggingEvent> logCapture;
    private Logger emailSenderLogger;

    @BeforeEach
    void captureEmailLog() {
        emailSenderLogger = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        emailSenderLogger.addAppender(logCapture);
        emailSenderLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void releaseEmailLog() {
        if (emailSenderLogger != null && logCapture != null) {
            emailSenderLogger.detachAppender(logCapture);
            logCapture.stop();
        }
    }

    // --- Issuing ----------------------------------------------------------------------------

    @Test
    @DisplayName("FT-CONF-01: registering emails a 6-digit code and leaves the account unconfirmed")
    void registrationSendsACode() {
        String email = uniqueEmail("newcomer");

        ResponseEntity<String> response =
                post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));

        assertThat(statusOf(response)).isEqualTo(201);
        assertThat(jsonOf(response).getJSONObject("user").getBoolean("emailVerified")).isFalse();
        assertThat(capturedCode()).hasSize(6).containsOnlyDigits();
    }

    @Test
    @DisplayName("FT-CONF-02: the code confirms the account, and a new session reflects it")
    void theCodeConfirmsTheAccount() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));

        assertThat(statusOf(verify(email, capturedCode()))).isEqualTo(200);

        Actor loggedIn = login(email, PASSWORD);
        assertThat(userOf(loggedIn).getBoolean("emailVerified")).isTrue();
    }

    @Test
    @DisplayName("FT-CONF-03: the code is stored hashed, never in plaintext")
    void theCodeIsStoredHashed() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));
        String code = capturedCode();

        String storedHash = jdbc.queryForObject(
                "SELECT token_hash FROM email_verification_tokens t "
                        + "JOIN users u ON u.id = t.user_id WHERE u.email = ?",
                String.class, email);

        assertThat(storedHash).hasSize(64).isNotEqualTo(code);
    }

    @Test
    @DisplayName("FT-CONF-04: a malformed code is a 400 and doesn't spend an attempt")
    void aMalformedCodeIsRejectedBeforeItCosts() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));

        assertThat(statusOf(verify(email, "abc"))).isEqualTo(400);

        assertThat(attemptsFor(email)).as("validation rejection shouldn't burn a guess").isZero();
    }

    // --- The guess limit --------------------------------------------------------------------

    @Test
    @DisplayName("FT-CONF-05: a wrong code is rejected and counts against the budget")
    void aWrongCodeCountsAgainstTheBudget() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));
        String realCode = capturedCode();

        assertThat(statusOf(verify(email, wrongCodeOtherThan(realCode)))).isEqualTo(401);
        assertThat(attemptsFor(email)).isEqualTo(1);

        // One wrong guess isn't fatal — the real code still works.
        assertThat(statusOf(verify(email, realCode))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-CONF-06: the code is burned after 5 wrong guesses, and the right one stops working")
    void theCodeIsBurnedAfterTooManyWrongGuesses() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));
        String realCode = capturedCode();
        String wrong = wrongCodeOtherThan(realCode);

        // Worth noting what this proves: the harness gives every request its own synthetic
        // client IP, so these five guesses arrive from five different addresses. The cap still
        // holds, which is the whole reason it's counted against the code row rather than
        // per-IP — an attacker rotating IPs gets no extra guesses.
        for (int i = 0; i < EmailVerificationToken.MAX_ATTEMPTS; i++) {
            assertThat(statusOf(verify(email, wrong))).isEqualTo(401);
        }

        // The correct code must fail too. If it still worked, the cap would only slow an
        // attacker down rather than stop them — they'd simply guess right on the last attempt.
        assertThat(statusOf(verify(email, realCode))).isEqualTo(401);
        assertThat(userOf(login(email, PASSWORD)).getBoolean("emailVerified")).isFalse();
    }

    @Test
    @DisplayName("FT-CONF-07: resending after a burnout gives a working code and a fresh budget")
    void resendingRecoversFromABurnout() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));
        String wrong = wrongCodeOtherThan(capturedCode());
        for (int i = 0; i < EmailVerificationToken.MAX_ATTEMPTS; i++) {
            verify(email, wrong);
        }

        assertThat(statusOf(post("/api/v1/auth/resend-verification", null,
                new JSONObject().put("email", email)))).isEqualTo(200);

        assertThat(statusOf(verify(email, capturedCode()))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-CONF-08: resending invalidates the earlier code")
    void resendingInvalidatesTheEarlierCode() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));
        String firstCode = capturedCode();

        post("/api/v1/auth/resend-verification", null, new JSONObject().put("email", email));
        String secondCode = capturedCode();
        assertThat(secondCode).isNotEqualTo(firstCode);

        // Otherwise every resend leaves another working code — and another guess budget.
        assertThat(statusOf(verify(email, firstCode))).isEqualTo(401);
        assertThat(statusOf(verify(email, secondCode))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-CONF-09: rejections are indistinguishable from each other")
    void everyRejectionLooksTheSame() {
        String email = uniqueEmail("newcomer");
        post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));

        String wrongCode = messageOf(verify(email, wrongCodeOtherThan(capturedCode())));
        String unknownAddress = messageOf(verify(uniqueEmail("ghost"), "000000"));

        // Otherwise the endpoint answers "is this address registered?" for anyone who asks.
        assertThat(wrongCode).isEqualTo(unknownAddress);
    }

    @Test
    @DisplayName("FT-CONF-10: resend for an unknown address looks identical and sends nothing")
    void resendForAnUnknownAddressLeaksNothing() {
        ResponseEntity<String> response = post("/api/v1/auth/resend-verification", null,
                new JSONObject().put("email", uniqueEmail("ghost")));

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(logCapture.list).isEmpty();
    }

    // --- The gate ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-CONF-11: an unconfirmed account can't create a draft — 403, not 401")
    void unconfirmedAccountsCannotCreateADraft() {
        Actor newcomer = registerUnconfirmedUser();

        ResponseEntity<String> response = post("/api/v1/experiences", newcomer, Payloads.experience());

        // 403 specifically. A 401 would tell the web client its session is dead and send it into
        // a refresh-then-logout, which is the wrong answer to "confirm your email".
        assertThat(statusOf(response)).isEqualTo(403);
        assertThat(messageOf(response)).contains("Confirm your email");
    }

    @Test
    @DisplayName("FT-CONF-12: an unconfirmed account can't open a checkout")
    void unconfirmedAccountsCannotPurchase() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID published = publishedExperience(contributor, admin);
        Actor newcomer = registerUnconfirmedUser();

        ResponseEntity<String> response =
                post("/api/v1/experiences/" + published + "/purchase", newcomer, null);

        assertThat(statusOf(response)).isEqualTo(403);
        assertThat(messageOf(response)).contains("Confirm your email");
    }

    @Test
    @DisplayName("FT-CONF-13: an unconfirmed account can still log in, browse and read")
    void unconfirmedAccountsCanStillReadTheMarketplace() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID published = publishedExperience(contributor, admin);
        Actor newcomer = registerUnconfirmedUser();

        // Gating these would cost signups without protecting anything.
        assertThat(statusOf(get("/api/v1/experiences", newcomer))).isEqualTo(200);
        assertThat(statusOf(get("/api/v1/experiences/" + published, newcomer))).isEqualTo(200);
        assertThat(statusOf(get("/api/v1/experiences/mine", newcomer))).isEqualTo(200);
        assertThat(statusOf(get("/api/v1/payouts/mine", newcomer))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-CONF-14: confirming lifts the gate immediately, with no new token needed")
    void confirmingLiftsTheGateWithoutANewToken() {
        String email = uniqueEmail("newcomer");
        Actor newcomer = registerUnconfirmedUser("New Comer", email);
        assertThat(statusOf(post("/api/v1/experiences", newcomer, Payloads.experience()))).isEqualTo(403);

        verify(email, capturedCode());

        // Same access token as before — the guard reads the database rather than a JWT claim
        // precisely so a confirmation takes effect without waiting 15 minutes for a refresh.
        assertThat(statusOf(post("/api/v1/experiences", newcomer, Payloads.experience()))).isEqualTo(201);
    }

    @Test
    @DisplayName("FT-CONF-15: a Google sign-in is confirmed on arrival and gets no code")
    void googleSignInsAreConfirmedWithoutACode() {
        // StubGoogleIdTokenVerifier vouches for whatever it's handed — the real verifier already
        // refuses an ID token whose email_verified claim isn't set.
        String email = uniqueEmail("google-user");
        ResponseEntity<String> response = post("/api/v1/auth/google", null,
                new JSONObject().put("idToken",
                        StubGoogleIdTokenVerifier.validToken("google-sub-conf", email, "Google User")));

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(jsonOf(response).getJSONObject("user").getBoolean("emailVerified")).isTrue();
        assertThat(logCapture.list).isEmpty();
    }

    // --- Helpers ----------------------------------------------------------------------------

    private ResponseEntity<String> verify(String email, String code) {
        return post("/api/v1/auth/verify-email", null,
                new JSONObject().put("email", email).put("code", code));
    }

    /** The code from the most recently sent message. */
    private String capturedCode() {
        List<ILoggingEvent> events = List.copyOf(logCapture.list);
        String matched = null;
        for (ILoggingEvent event : events) {
            Matcher matcher = CODE_IN_EMAIL.matcher(event.getFormattedMessage());
            while (matcher.find()) {
                matched = matcher.group(1);
            }
        }
        assertThat(matched).as("expected a 6-digit code in the sent email; captured: %s", events).isNotNull();
        return matched;
    }

    /** A code guaranteed to be wrong, so a test can't fail one-in-a-million times. */
    private static String wrongCodeOtherThan(String realCode) {
        return realCode.equals("000000") ? "111111" : "000000";
    }

    private int attemptsFor(String email) {
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM email_verification_tokens t "
                        + "JOIN users u ON u.id = t.user_id WHERE u.email = ? "
                        + "ORDER BY t.created_at DESC LIMIT 1",
                Integer.class, email);
        return attempts == null ? 0 : attempts;
    }

    private JSONObject userOf(Actor actor) {
        return jsonOf(post("/api/v1/auth/login", null, loginBody(actor.email(), PASSWORD)))
                .getJSONObject("user");
    }
}
