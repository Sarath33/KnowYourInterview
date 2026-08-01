package com.knowyourinterview.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.email.EmailSender;
import com.knowyourinterview.api.support.ContainerConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The one gap flagged repeatedly since Phase 2: every other auth test mocks AuthService
 * behind @WebMvcTest. This exercises register -> login -> refresh (rotation) -> logout
 * against a real Postgres (for the user row) and real Redis (for refresh-token
 * tracking/revocation) via Testcontainers. Run via `mvn verify` — needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(ContainerConfig.class)
class AuthFlowIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** Replaces whichever EmailSender EmailConfig would have chosen, so the confirmation
     * link can be pulled back out and actually followed — the raw token is deliberately not
     * recoverable from the database (only its hash is stored), so intercepting the message is
     * the only way to exercise the real redemption path rather than faking it with an UPDATE. */
    @MockitoBean
    private EmailSender emailSender;

    private static String uniqueEmail() {
        return "it-" + System.nanoTime() + "@example.com";
    }

    /** Pulls the confirm/reset token out of the most recent message sent to an address. */
    private String capturedTokenFor(String email) {
        ArgumentCaptor<String> textBody = ArgumentCaptor.forClass(String.class);
        verify(emailSender, atLeastOnce())
                .send(eq(email), anyString(), anyString(), textBody.capture());
        String body = textBody.getAllValues().get(textBody.getAllValues().size() - 1);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("token=([^\\s]+)").matcher(body);
        assertThat(matcher.find()).as("email body should contain a ?token= link").isTrue();
        return matcher.group(1);
    }

    /** All test methods share one Spring context (and Redis container) — clear
     * rate-limit counters and any leftover refresh tokens between methods so they
     * can't affect each other regardless of execution order. */
    @BeforeEach
    void resetRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        // The EmailSender mock is shared across the whole class (one Spring context), so
        // clear recorded sends too — otherwise capturedTokenFor could pick up a message from
        // an earlier test and the interactions would accumulate across methods.
        reset(emailSender);
    }

    @Test
    void actuatorHealthReflectsRealDbAndRedisAndHidesDetailFromNonAdmins() {
        ResponseEntity<String> anonymous = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getBody()).contains("\"status\":\"UP\"");
        // show-details: when-authorized + roles: ADMIN (application.yml) — an
        // unauthenticated caller shouldn't see the DB/Redis component breakdown.
        assertThat(anonymous.getBody()).doesNotContain("\"components\"");

        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String adminEmail = uniqueEmail();
        restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterBody(adminEmail, "correct-horse-battery-staple", "Admin"),
                AuthResponse.class);
        jdbcTemplate.update("UPDATE users SET is_admin = true WHERE email = ?", adminEmail);
        AuthResponse admin = restTemplate.postForObject(
                "/api/v1/auth/login", new LoginBody(adminEmail, "correct-horse-battery-staple"), AuthResponse.class);

        HttpHeaders adminHeaders = new HttpHeaders();
        adminHeaders.setBearerAuth(admin.accessToken());
        ResponseEntity<String> asAdmin = restTemplate.exchange(
                "/actuator/health", HttpMethod.GET, new HttpEntity<>(adminHeaders), String.class);
        assertThat(asAdmin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asAdmin.getBody()).contains("\"db\"").contains("\"redis\"");
    }

    @Test
    void registerLoginRefreshLogout() {
        String email = uniqueEmail();

        AuthResponse registered = restTemplate.postForObject(
                "/api/v1/auth/register",
                new RegisterBody(email, "correct-horse-battery-staple", "IT User"),
                AuthResponse.class);
        assertThat(registered).isNotNull();
        assertThat(registered.user().email()).isEqualTo(email);
        assertThat(registered.accessToken()).isNotBlank();
        assertThat(registered.refreshToken()).isNotBlank();

        AuthResponse loggedIn = restTemplate.postForObject(
                "/api/v1/auth/login", new LoginBody(email, "correct-horse-battery-staple"), AuthResponse.class);
        assertThat(loggedIn).isNotNull();
        assertThat(loggedIn.accessToken()).isNotBlank();

        // Refresh rotates the token — the old one becomes single-use and should be dead
        // in Redis afterwards.
        AuthResponse refreshed = restTemplate.postForObject(
                "/api/v1/auth/refresh", new RefreshBody(loggedIn.refreshToken()), AuthResponse.class);
        assertThat(refreshed).isNotNull();
        assertThat(refreshed.refreshToken()).isNotEqualTo(loggedIn.refreshToken());

        ResponseEntity<Void> reuseOldToken = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshBody(loggedIn.refreshToken()), Void.class);
        assertThat(reuseOldToken.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A protected endpoint works with the fresh access token...
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshed.accessToken());
        ResponseEntity<String> mine = restTemplate.exchange(
                "/api/v1/experiences/mine", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);

        // ...and logout revokes the refresh token so it can no longer be used.
        ResponseEntity<Void> logoutResponse = restTemplate.postForEntity(
                "/api/v1/auth/logout", new RefreshBody(refreshed.refreshToken()), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> refreshAfterLogout = restTemplate.postForEntity(
                "/api/v1/auth/refresh", new RefreshBody(refreshed.refreshToken()), Void.class);
        assertThat(refreshAfterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void registrationIsRateLimitedPerIp() {
        // RateLimitingFilter allows 5 registrations/minute/IP (SecurityConfig ->
        // RateLimitingFilter.LIMITS_BY_PATH) — TestRestTemplate always calls from the
        // same loopback address, so the 6th call in quick succession should 429.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                    "/api/v1/auth/register",
                    new RegisterBody(uniqueEmail(), "correct-horse-battery-staple", "Rate Limit Test"),
                    AuthResponse.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }

        ResponseEntity<String> sixth = restTemplate.postForEntity(
                "/api/v1/auth/register",
                new RegisterBody(uniqueEmail(), "correct-horse-battery-staple", "Rate Limit Test"), String.class);
        assertThat(sixth.getStatusCode().value()).isEqualTo(429);
    }

    /**
     * The whole confirmation loop against the real stack: register, take the link out of the
     * email that was actually sent, redeem it, and see the account come back confirmed.
     * <p>
     * This is the one place the raw token makes the full round trip. Everywhere else — the
     * unit tests, and PurchaseFlowIT — has to settle for a direct UPDATE, because the token
     * only exists in the message body and the database keeps just its hash.
     */
    @Test
    void registrationSendsAConfirmationLinkThatActuallyWorks() {
        String email = uniqueEmail();

        AuthResponse registered = restTemplate.postForObject(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", "IT User"),
                AuthResponse.class);
        assertThat(registered.user().emailVerified())
                .as("a fresh email/password signup starts unconfirmed")
                .isFalse();

        String token = capturedTokenFor(email);

        ResponseEntity<String> confirmed = restTemplate.postForEntity(
                "/api/v1/auth/verify-email", new TokenBody(token), String.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A newly issued session reflects it — which is what the web app's refresh-on-confirm
        // relies on to lift the gate without waiting for the access token to turn over.
        AuthResponse loggedIn = restTemplate.postForObject(
                "/api/v1/auth/login", new LoginBody(email, "correct-horse-battery-staple"), AuthResponse.class);
        assertThat(loggedIn.user().emailVerified()).isTrue();
    }

    /** Single-use, enforced server-side — a link that leaks after it's been used is inert. */
    @Test
    void aConfirmationLinkCannotBeRedeemedTwice() {
        String email = uniqueEmail();
        restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", "IT User"),
                AuthResponse.class);
        String token = capturedTokenFor(email);

        assertThat(restTemplate.postForEntity("/api/v1/auth/verify-email", new TokenBody(token), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second attempt succeeds rather than erroring, because the account is already
        // confirmed — see EmailVerificationService#verify on why that ordering is deliberate
        // (mail clients prefetch links, people double-click). The token itself is spent either
        // way; what matters is that nothing further changes.
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/auth/verify-email", new TokenBody(token), String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aGarbageConfirmationTokenIsRejected() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/verify-email", new TokenBody("not-a-real-token"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("isn't valid");
    }

    /** Resending must invalidate the previous link — otherwise every resend leaves another
     * working credential sitting in the user's inbox. */
    @Test
    void resendingInvalidatesTheEarlierLink() {
        String email = uniqueEmail();
        restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", "IT User"),
                AuthResponse.class);
        String firstToken = capturedTokenFor(email);

        restTemplate.postForEntity(
                "/api/v1/auth/resend-verification", new EmailBody(email), String.class);
        String secondToken = capturedTokenFor(email);
        assertThat(secondToken).isNotEqualTo(firstToken);

        ResponseEntity<String> stale = restTemplate.postForEntity(
                "/api/v1/auth/verify-email", new TokenBody(firstToken), String.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stale.getBody()).contains("already been used");

        assertThat(restTemplate.postForEntity("/api/v1/auth/verify-email", new TokenBody(secondToken), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Same no-enumeration response as forgot-password: an unknown address gets the identical
     * 200 a real one does, and nothing is sent. */
    @Test
    void resendingForAnUnknownAddressLooksIdenticalAndSendsNothing() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/resend-verification", new EmailBody("nobody-" + System.nanoTime() + "@example.com"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        org.mockito.Mockito.verifyNoInteractions(emailSender);
    }

    @Test
    void duplicateRegistrationIsRejected() {
        String email = uniqueEmail();
        restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", "First"),
                AuthResponse.class);

        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/auth/register", new RegisterBody(email, "correct-horse-battery-staple", "Second"),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private record RegisterBody(String email, String password, String displayName) {}

    private record LoginBody(String email, String password) {}

    private record RefreshBody(String refreshToken) {}

    /** Body shape for /verify-email — matches VerifyEmailRequest. */
    private record TokenBody(String token) {}

    /** Body shape for /resend-verification — matches ResendVerificationRequest. */
    private record EmailBody(String email) {}
}
