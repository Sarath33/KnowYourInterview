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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.support.ContainerConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one gap flagged repeatedly since Phase 2: every other auth test mocks AuthService
 * behind @WebMvcTest. This exercises register -> login -> refresh (rotation) -> logout
 * against a real Postgres (for the user row) and real Redis (for refresh-token
 * tracking/revocation) via Testcontainers. Run via `mvn verify` — needs Docker.
 *
 * <p>Email confirmation isn't covered here — {@code EmailConfirmationFunctionalIT} does that
 * properly, including redeeming a real emailed link. Registration does now send a confirmation
 * message, which is why the mail host is pinned blank below.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(ContainerConfig.class)
class AuthFlowIT {

    /**
     * Guarantees no test can open an SMTP connection: with a blank host, EmailConfig selects
     * LoggingEmailSender. Pinned rather than left to the {@code ${MAIL_HOST:}} default, because
     * a developer with MAIL_HOST exported in their shell would otherwise have this suite mail
     * the fake @example.com addresses it registers.
     */
    @DynamicPropertySource
    static void noRealEmail(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "");
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    private static String uniqueEmail() {
        return "it-" + System.nanoTime() + "@example.com";
    }

    /** All test methods share one Spring context (and Redis container) — clear
     * rate-limit counters and any leftover refresh tokens between methods so they
     * can't affect each other regardless of execution order. */
    @BeforeEach
    void resetRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    /**
     * Also the regression test for a healthcheck outage this suite would have caught if it had
     * been run: adding spring-boot-starter-mail auto-registers Actuator's MailHealthIndicator
     * whenever a JavaMailSender bean exists, and that bean exists even with no mail configured
     * (`spring.mail.host: ${MAIL_HOST:}` is present-but-empty, which is all Spring Boot's mail
     * auto-configuration checks for). The indicator opened an SMTP connection to a blank host,
     * failed, and took the aggregate health DOWN — a 503 on the exact path Railway polls, so
     * every deploy was marked FAILED while the app itself ran perfectly. Fixed by
     * `management.health.mail.enabled: false`; see the reasoning in application.yml.
     * <p>
     * This runs with mail unconfigured (the class pins a blank host, same as production had),
     * so a regression puts this test straight back to red.
     */
    @Test
    void actuatorHealthReflectsRealDbAndRedisAndHidesDetailFromNonAdmins() {
        ResponseEntity<String> anonymous = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(anonymous.getStatusCode())
                .as("a 503 here is what fails the platform healthcheck and rolls back the deploy")
                .isEqualTo(HttpStatus.OK);
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
        // The component that must NOT be here. Its presence means the mail indicator got
        // re-enabled, which puts an unreachable SMTP server back in charge of whether the
        // platform considers this service alive.
        assertThat(asAdmin.getBody())
                .as("mail must not be a health component — see management.health.mail.enabled")
                .doesNotContain("\"mail\"");
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
}
