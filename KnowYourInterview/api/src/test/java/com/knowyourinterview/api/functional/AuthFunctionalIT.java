package com.knowyourinterview.api.functional;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-AUTH — registration, login, token lifecycle, token forgery, and rate limiting, against real
 * Postgres and real Redis. See {@code docs/09-test-plan.md} §7.1.
 *
 * <p>Distinct from {@code AuthServiceTest} (all collaborators mocked) and {@code AuthFlowIT}
 * (one happy path): everything here depends on something a mock would have faked away — a real
 * BCrypt hash in a real column, a real Redis key with a real TTL, the real filter chain deciding
 * 401 vs 403, and the real JWT signature check.
 */
class AuthFunctionalIT extends FunctionalTestBase {

    // --- Registration -----------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTH-01: register issues a session that actually works")
    void registerIssuesAUsableSession() {
        String email = uniqueEmail("newcomer");

        ResponseEntity<String> response =
                post("/api/v1/auth/register", null, registerBody(email, PASSWORD, "New Comer"));

        assertThat(statusOf(response)).isEqualTo(201);
        JSONObject body = jsonOf(response);
        assertThat(body.getString("accessToken")).isNotBlank();
        assertThat(body.getString("refreshToken")).isNotBlank();
        JSONObject user = body.getJSONObject("user");
        assertThat(user.getString("email")).isEqualTo(email);
        assertThat(user.getString("displayName")).isEqualTo("New Comer");
        // The field is isAdmin, not admin — shared/types.ts depends on that exact name.
        assertThat(user.getBoolean("isAdmin")).isFalse();

        Actor actor = login(email, PASSWORD);
        assertThat(statusOf(get("/api/v1/experiences/mine", actor))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-AUTH-02: the password is stored as a BCrypt hash, never in cleartext")
    void passwordIsStoredHashed() {
        Actor actor = registerUser();

        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, actor.id());

        assertThat(storedHash).isNotNull();
        assertThat(storedHash).isNotEqualTo(PASSWORD);
        assertThat(storedHash).doesNotContain(PASSWORD);
        assertThat(storedHash).startsWith("$2");
    }

    @Test
    @DisplayName("FT-AUTH-03: a duplicate email is rejected and creates no second row")
    void duplicateEmailIsRejected() {
        String email = uniqueEmail("dupe");
        registerUser("First", email);

        ResponseEntity<String> second = post("/api/v1/auth/register", null,
                registerBody(email.toUpperCase(), PASSWORD, "Second"));

        assertThat(statusOf(second)).isEqualTo(409);
        assertThat(countRows("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-AUTH-04: login matches the email case-insensitively")
    void loginIsCaseInsensitive() {
        String email = uniqueEmail("MixedCase");
        registerUser("Mixed Case", email);

        ResponseEntity<String> response =
                post("/api/v1/auth/login", null, loginBody(email.toUpperCase(), PASSWORD));

        assertThat(statusOf(response)).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-AUTH-06: registration validation rejects weak or malformed input")
    void registrationValidationRejectsBadInput() {
        Map<String, JSONObject> invalidBodies = new LinkedHashMap<>();
        invalidBodies.put("password", registerBody(uniqueEmail("short"), "1234567", "Short Password"));
        invalidBodies.put("email", registerBody("not-an-email", PASSWORD, "Bad Email"));
        invalidBodies.put("displayName", registerBody(uniqueEmail("noname"), PASSWORD, "  "));

        invalidBodies.forEach((expectedField, body) -> {
            ResponseEntity<String> response = post("/api/v1/auth/register", null, body);

            assertThat(statusOf(response))
                    .as("expected 400 for invalid %s", expectedField)
                    .isEqualTo(400);
            JSONObject error = jsonOf(response);
            assertThat(error.getString("message")).isEqualTo("Validation failed");
            assertThat(error.getJSONObject("fieldErrors").keySet())
                    .as("fieldErrors should name the offending field")
                    .contains(expectedField);
        });

        assertThat(countRows("users")).isZero();
    }

    // --- Credentials ------------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTH-05: a wrong password and an unknown email are indistinguishable")
    void badCredentialsDoNotEnumerateAccounts() {
        Actor actor = registerUser();

        ResponseEntity<String> wrongPassword =
                post("/api/v1/auth/login", null, loginBody(actor.email(), "not-the-password"));
        ResponseEntity<String> unknownEmail =
                post("/api/v1/auth/login", null, loginBody(uniqueEmail("ghost"), PASSWORD));

        assertThat(statusOf(wrongPassword)).isEqualTo(401);
        assertThat(statusOf(unknownEmail)).isEqualTo(401);
        // Same message both ways: a difference here would tell an attacker which emails are
        // registered, which is the whole point of the generic wording in ApiExceptionHandler.
        assertThat(messageOf(wrongPassword)).isEqualTo("Invalid email or password");
        assertThat(messageOf(unknownEmail)).isEqualTo(messageOf(wrongPassword));
    }

    // --- Token lifecycle --------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTH-07: refresh rotates the token and the old one stops working")
    void refreshRotatesAndInvalidatesTheOldToken() {
        Actor actor = registerUser();

        ResponseEntity<String> refreshed = post("/api/v1/auth/refresh", null,
                new JSONObject().put("refreshToken", actor.refreshToken()));

        assertThat(statusOf(refreshed)).isEqualTo(200);
        String rotated = jsonOf(refreshed).getString("refreshToken");
        assertThat(rotated).isNotEqualTo(actor.refreshToken());

        ResponseEntity<String> reuse = post("/api/v1/auth/refresh", null,
                new JSONObject().put("refreshToken", actor.refreshToken()));
        assertThat(statusOf(reuse)).isEqualTo(401);

        // The rotated one is still good — rotation must not lock the user out.
        assertThat(statusOf(post("/api/v1/auth/refresh", null,
                new JSONObject().put("refreshToken", rotated)))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-AUTH-08: logout revokes the refresh token in Redis")
    void logoutRevokesTheRefreshToken() {
        Actor actor = registerUser();
        assertThat(redis.keys("refresh:*")).isNotEmpty();

        ResponseEntity<String> logout = post("/api/v1/auth/logout", null,
                new JSONObject().put("refreshToken", actor.refreshToken()));

        assertThat(statusOf(logout)).isEqualTo(204);
        assertThat(redis.keys("refresh:*")).isEmpty();
        assertThat(statusOf(post("/api/v1/auth/refresh", null,
                new JSONObject().put("refreshToken", actor.refreshToken())))).isEqualTo(401);
    }

    @Test
    @DisplayName("FT-AUTH-09: logout is idempotent and tolerates a junk token")
    void logoutIsIdempotent() {
        Actor actor = registerUser();
        JSONObject body = new JSONObject().put("refreshToken", actor.refreshToken());

        assertThat(statusOf(post("/api/v1/auth/logout", null, body))).isEqualTo(204);
        assertThat(statusOf(post("/api/v1/auth/logout", null, body))).isEqualTo(204);
        assertThat(statusOf(post("/api/v1/auth/logout", null,
                new JSONObject().put("refreshToken", "not-even-a-jwt")))).isEqualTo(204);
    }

    @Test
    @DisplayName("FT-AUTH-10: a refresh token is not accepted as an access token")
    void refreshTokenIsNotAnAccessToken() {
        Actor actor = registerUser();

        ResponseEntity<String> response = exchange(
                HttpMethod.GET, "/api/v1/experiences/mine", headers(actor.refreshToken()), null);

        // Both token types are signed with the same key, so only JwtService's "typ" claim check
        // stands between a 30-day refresh token and 30 days of full API access.
        assertThat(statusOf(response)).isEqualTo(401);
    }

    @Test
    @DisplayName("FT-AUTH-11: a tampered access token is rejected")
    void tamperedTokenIsRejected() {
        Actor actor = registerUser();
        String token = actor.accessToken();
        char lastChar = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (lastChar == 'A' ? 'B' : 'A');

        assertThat(statusOf(exchange(HttpMethod.GET, "/api/v1/experiences/mine", headers(tampered), null)))
                .isEqualTo(401);
    }

    @Test
    @DisplayName("FT-AUTH-12: a self-signed token claiming admin is rejected")
    void forgedAdminClaimIsRejected() {
        Actor actor = registerUser();

        // Swap in a payload asserting admin:true while keeping the original signature. The
        // signature no longer matches the payload, which is exactly what has to be caught — an
        // attacker who could edit claims would own approve, publish, and mark-paid.
        String[] parts = actor.accessToken().split("\\.");
        assertThat(parts).hasSize(3);
        long nowSeconds = System.currentTimeMillis() / 1000;
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new JSONObject()
                        .put("sub", actor.id().toString())
                        .put("email", actor.email())
                        .put("admin", true)
                        .put("typ", "access")
                        .put("iat", nowSeconds)
                        .put("exp", nowSeconds + 3600)
                        .toString().getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThat(statusOf(exchange(HttpMethod.GET, "/api/v1/admin/experiences", headers(forged), null)))
                .isEqualTo(401);
        // And the genuine, unforged token still doesn't reach admin routes.
        assertThat(statusOf(get("/api/v1/admin/experiences", actor))).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-AUTH-13: malformed Authorization headers are rejected, not crashed on")
    void malformedAuthorizationHeadersAreRejected() {
        List<String> malformed = List.of("Bearer", "Bearer ", "Basic abc123", "just-a-string", "Bearer a.b.c");

        for (String value : malformed) {
            HttpHeaders headers = headers(null);
            headers.set("Authorization", value);

            ResponseEntity<String> response = exchange(HttpMethod.GET, "/api/v1/experiences/mine", headers, null);

            assertThat(statusOf(response))
                    .as("Authorization: '%s' should be a clean 401, never a 500", value)
                    .isEqualTo(401);
        }
    }

    // --- Rate limiting ----------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTH-14: login is throttled after 10 attempts from one address")
    void loginIsRateLimitedPerAddress() {
        Actor actor = registerUser();
        String attackerIp = "203.0.113.10";
        JSONObject wrongPassword = loginBody(actor.email(), "wrong-password");

        for (int i = 1; i <= 10; i++) {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/auth/login",
                    headersFromIp(null, attackerIp), wrongPassword);
            assertThat(statusOf(response))
                    .as("attempt %d should still be evaluated (401), not throttled", i)
                    .isEqualTo(401);
        }

        ResponseEntity<String> throttled = exchange(HttpMethod.POST, "/api/v1/auth/login",
                headersFromIp(null, attackerIp), wrongPassword);

        assertThat(statusOf(throttled)).isEqualTo(429);
        assertThat(messageOf(throttled)).contains("Too many attempts");
        // Even the correct password is refused while the bucket is full — the limiter runs
        // before authentication, which is the point of it.
        assertThat(statusOf(exchange(HttpMethod.POST, "/api/v1/auth/login",
                headersFromIp(null, attackerIp), loginBody(actor.email(), PASSWORD)))).isEqualTo(429);
    }

    @Test
    @DisplayName("FT-AUTH-15: throttling one address does not lock out everyone else")
    void rateLimitingIsPerAddressNotGlobal() {
        Actor actor = registerUser();
        String noisyIp = "203.0.113.20";
        String innocentIp = "203.0.113.21";

        for (int i = 0; i < 11; i++) {
            exchange(HttpMethod.POST, "/api/v1/auth/login", headersFromIp(null, noisyIp),
                    loginBody(actor.email(), "wrong-password"));
        }
        assertThat(statusOf(exchange(HttpMethod.POST, "/api/v1/auth/login",
                headersFromIp(null, noisyIp), loginBody(actor.email(), PASSWORD)))).isEqualTo(429);

        // This is the §1.3 regression: with the proxy hop misread, every user shares one bucket
        // and a single noisy client locks out the entire user base.
        ResponseEntity<String> innocent = exchange(HttpMethod.POST, "/api/v1/auth/login",
                headersFromIp(null, innocentIp), loginBody(actor.email(), PASSWORD));
        assertThat(statusOf(innocent)).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-AUTH-16: the rate-limit counter expires rather than lasting forever")
    void rateLimitWindowExpires() {
        String ip = "203.0.113.30";
        exchange(HttpMethod.POST, "/api/v1/auth/login", headersFromIp(null, ip),
                loginBody(uniqueEmail("nobody"), PASSWORD));

        String key = "ratelimit:/api/v1/auth/login:" + ip;
        Long ttlSeconds = redis.getExpire(key);

        // A key with no TTL would mean one burst bans that address permanently.
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("FT-AUTH-14b: registration is throttled after 5 attempts from one address")
    void registrationIsRateLimited() {
        String ip = "203.0.113.40";

        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = exchange(HttpMethod.POST, "/api/v1/auth/register",
                    headersFromIp(null, ip), registerBody(uniqueEmail("burst"), PASSWORD, "Burst"));
            assertThat(statusOf(response)).isEqualTo(201);
        }

        ResponseEntity<String> sixth = exchange(HttpMethod.POST, "/api/v1/auth/register",
                headersFromIp(null, ip), registerBody(uniqueEmail("burst"), PASSWORD, "Burst"));

        assertThat(statusOf(sixth)).isEqualTo(429);
        assertThat(countRows("users")).isEqualTo(5);
    }
}
