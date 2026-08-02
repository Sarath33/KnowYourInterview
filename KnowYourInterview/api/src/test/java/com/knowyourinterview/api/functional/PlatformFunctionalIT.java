package com.knowyourinterview.api.functional;

import java.util.List;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-OPS — the things that are true of the whole application rather than of any one feature:
 * the migrated schema, the security response headers, CORS, and the shape of an error. See
 * {@code docs/09-test-plan.md} §7.13.
 *
 * <p>Most of this is cheap to test and expensive to get wrong quietly. A dropped security header,
 * a CORS rule that stops matching the deployed web origin, or a 500 where a 4xx belongs are all
 * invisible until someone is looking at a broken page or a noisy alert channel.
 */
class PlatformFunctionalIT extends FunctionalTestBase {

    // --- Schema ------------------------------------------------------------------------------

    @Test
    @DisplayName("FT-OPS-01: every Flyway migration applied cleanly")
    void everyMigrationAppliedCleanly() {
        Long failed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Long.class);
        Long applied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Long.class);

        assertThat(failed).as("a failed migration leaves the schema half-built").isZero();
        // Deliberately a lower bound rather than an exact count: this shouldn't need editing
        // every time a migration is added, only if one goes missing.
        assertThat(applied).isGreaterThanOrEqualTo(11L);
    }

    @Test
    @DisplayName("FT-OPS-02: every entity maps onto the migrated schema")
    void everyEntityMapsOntoTheMigratedSchema() {
        // The application runs with spring.jpa.hibernate.ddl-auto=validate, so the context
        // simply would not have started if an entity and its table had drifted apart — this
        // whole suite depends on that having held. Asserting it explicitly turns an obscure
        // startup failure into a named test, and checks the tables are actually reachable.
        List<String> tables = List.of(
                "users", "experiences", "experience_rounds", "proof_documents",
                "experience_edit_snapshots", "experience_views", "purchases", "entitlements",
                "payouts", "payout_accounts", "review_logs", "password_reset_tokens",
                "email_verification_tokens");

        for (String table : tables) {
            assertThat(countRows(table)).as("%s should be queryable and empty after reset", table).isZero();
        }
    }

    // --- Health ------------------------------------------------------------------------------

    @Test
    @DisplayName("FT-OPS-03/04: health endpoints answer without authentication")
    void healthEndpointsAnswerAnonymously() {
        ResponseEntity<String> appHealth = getAnonymously("/api/v1/health");

        assertThat(statusOf(appHealth)).isEqualTo(200);
        assertThat(appHealth.getBody()).contains("UP");

        // Liveness and readiness are what a container orchestrator probes, and it has no
        // credentials — if these ever start requiring auth, deployments fail their health check
        // and roll back.
        assertThat(statusOf(getAnonymously("/actuator/health/liveness"))).isEqualTo(200);
        assertThat(statusOf(getAnonymously("/actuator/health/readiness"))).isEqualTo(200);
    }

    // --- Security headers ----------------------------------------------------------------------

    @Test
    @DisplayName("FT-OPS-05: Spring Security's default response headers are still in place")
    void securityResponseHeadersArePresent() {
        Actor actor = registerUser();

        HttpHeaders headers = get("/api/v1/experiences/mine", actor).getHeaders();

        // These come free from Spring Security unless someone calls .headers(...) to turn them
        // off. This test exists so that turning them off is a deliberate, visible act.
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Cache-Control")).contains("no-store");
    }

    // --- CORS ----------------------------------------------------------------------------------

    @Test
    @DisplayName("FT-OPS-06: preflight succeeds for the web app's origin on an authenticated route")
    void preflightSucceedsForTheWebAppOrigin() {
        HttpHeaders preflight = headers(null);
        preflight.set(HttpHeaders.ORIGIN, "http://localhost:5173");
        preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization");

        ResponseEntity<String> response =
                exchange(HttpMethod.OPTIONS, "/api/v1/experiences/mine", preflight, null);

        // The Phase 3 regression: CORS configured at the MVC layer never saw preflight, because
        // Spring Security's filter chain sits in front of the dispatcher. Every authenticated
        // call sends an Authorization header, so every authenticated call preflights — which is
        // why this broke everything except /health and /auth/**.
        assertThat(statusOf(response)).isIn(200, 204);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .isEqualTo("http://localhost:5173");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                .contains("GET");
    }

    @Test
    @DisplayName("FT-OPS-07: preflight from a foreign origin is refused")
    void preflightFromAForeignOriginIsRefused() {
        HttpHeaders preflight = headers(null);
        preflight.set(HttpHeaders.ORIGIN, "https://evil.example");
        preflight.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        ResponseEntity<String> response =
                exchange(HttpMethod.OPTIONS, "/api/v1/experiences", preflight, null);

        // A permissive Access-Control-Allow-Origin here would let any page on the internet make
        // authenticated calls with a token it tricked out of a user.
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .as("a foreign origin must never be echoed back as allowed")
                .isNotEqualTo("https://evil.example");
        assertThat(statusOf(response)).isNotIn(200, 204);
    }

    // --- Error contract --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-OPS-08: every error body has the same shape")
    void errorBodiesShareOneShape() {
        Actor actor = registerUser();
        Actor stranger = registerUser();
        UUID someoneElsesDraft = createDraft(stranger);

        // One of each family that ApiExceptionHandler owns: not-found, invalid-state,
        // forbidden, invalid-token. Deliberately *not* a Spring Security 403 — that never
        // reaches the advice, so it comes back in the container's own error shape (no
        // `message` field). Worth knowing, and worth not asserting a shared shape over.
        List<ResponseEntity<String>> errors = List.of(
                get("/api/v1/experiences/" + UUID.randomUUID(), actor),
                post("/api/v1/experiences/" + createDraft(actor) + "/submit", actor, null),
                put("/api/v1/experiences/" + someoneElsesDraft, actor, Payloads.experience()),
                post("/api/v1/auth/refresh", null, new JSONObject().put("refreshToken", "nope")));

        for (ResponseEntity<String> error : errors) {
            JSONObject body = jsonOf(error);
            assertThat(body.has("timestamp")).as("body: %s", error.getBody()).isTrue();
            assertThat(body.getInt("status")).isEqualTo(statusOf(error));
            assertThat(body.getString("error")).isNotBlank();
            assertThat(body.getString("message")).isNotBlank();
            // fieldErrors is only present for validation failures — a client keying off it
            // shouldn't have to handle an empty map on every other error.
            assertThat(body.has("fieldErrors")).isFalse();
        }

        JSONObject validation = jsonOf(post("/api/v1/experiences", actor,
                Payloads.experience().put("company", "")));
        assertThat(validation.getString("message")).isEqualTo("Validation failed");
        assertThat(validation.getJSONObject("fieldErrors").keySet()).isNotEmpty();
    }

    @Test
    @DisplayName("FT-OPS-09: a wrong HTTP method is a 405, not a 500")
    void wrongHttpMethodIsA405() {
        ResponseEntity<String> response =
                exchange(HttpMethod.DELETE, "/api/v1/health", headers(null), null);

        // If this is 500, the defect is ApiExceptionHandler's catch-all @ExceptionHandler
        // (Exception.class): ExceptionHandlerExceptionResolver runs before Spring's own
        // DefaultHandlerExceptionResolver, so the catch-all swallows framework exceptions that
        // already have correct status mappings — 405 here, and 415/406 elsewhere. The fix is to
        // extend ResponseEntityExceptionHandler (or handle those types explicitly), not to
        // change this expectation. See docs/09-test-plan.md gap G9.
        assertThat(statusOf(response))
                .as("a wrong method should map to 405; a 500 here is finding G9")
                .isEqualTo(405);
    }

    @Test
    @DisplayName("FT-OPS-09b: an unsupported content type is a 415, not a 500")
    void unsupportedContentTypeIsA415() {
        Actor actor = registerUser();
        HttpHeaders headers = headers(actor.accessToken());
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = rest.exchange("/api/v1/experiences", HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(Payloads.experience().toString(), headers), String.class);

        assertThat(statusOf(response))
                .as("an unsupported content type should map to 415; a 500 here is finding G9")
                .isEqualTo(415);
    }

    @Test
    @DisplayName("FT-OPS-10: an unknown route never returns a stack trace")
    void unknownRouteIsHandledCleanly() {
        Actor actor = registerUser();

        ResponseEntity<String> anonymous = getAnonymously("/api/v1/no-such-endpoint");
        ResponseEntity<String> authenticated = get("/api/v1/no-such-endpoint", actor);

        // Anonymous is unambiguous: the catch-all authenticated() rule rejects it before routing
        // ever happens.
        assertThat(statusOf(anonymous)).isEqualTo(401);
        assertThat(statusOf(authenticated)).isNotEqualTo(200);
        assertThatBodyLeaksNoInternals(authenticated);
    }

    @Test
    @DisplayName("FT-OPS-11: an oversized upload is refused without a server error")
    void oversizedUploadIsRefused() {
        Actor contributor = registerUser();
        UUID experienceId = createDraft(contributor);
        // spring.servlet.multipart.max-file-size is 10MB.
        byte[] tooLarge = new byte[11 * 1024 * 1024];

        try {
            ResponseEntity<String> response = postFile("/api/v1/experiences/" + experienceId + "/proof",
                    contributor, "huge.pdf", "application/pdf", tooLarge);

            assertThat(statusOf(response))
                    .as("expected 413 Payload Too Large, got %s", response.getBody())
                    .isEqualTo(413);
        } catch (ResourceAccessException connectionClosed) {
            // Also acceptable, and not a defect: a container that hits its size limit mid-upload
            // may reset the connection rather than swallow the rest of the body to send a
            // response. What matters either way is the assertion below.
            assertThat(connectionClosed).isNotNull();
        }

        assertThat(countRows("proof_documents"))
                .as("an oversized upload must not be stored")
                .isZero();
    }

    @Test
    @DisplayName("FT-OPS-12: no error response ever leaks internals")
    void errorsNeverLeakInternals() {
        Actor actor = registerUser();

        List<ResponseEntity<String>> errors = List.of(
                get("/api/v1/experiences/not-a-uuid", actor),
                get("/api/v1/admin/payouts", actor),
                exchange(HttpMethod.POST, "/api/v1/experiences", headers(actor.accessToken()), "{not json"),
                post("/api/v1/auth/login", null, loginBody("nobody@example.test", "wrong-password")),
                post("/api/v1/purchases/confirm", actor, new JSONObject()
                        .put("razorpayOrderId", "order_x")
                        .put("razorpayPaymentId", "pay_x")
                        .put("razorpaySignature", "sig_x")));

        // A stack trace or a SQL fragment in an error body is free reconnaissance: it names the
        // framework, the ORM, the table structure, and often the file layout.
        errors.forEach(this::assertThatBodyLeaksNoInternals);
    }

    private void assertThatBodyLeaksNoInternals(ResponseEntity<String> response) {
        String body = response.getBody();
        if (body == null) {
            return;
        }
        assertThat(body)
                .as("error body leaked internals: %s", body)
                .doesNotContain("com.knowyourinterview")
                .doesNotContain("org.springframework")
                .doesNotContain("org.hibernate")
                .doesNotContain("java.lang")
                .doesNotContain("at com.")
                .doesNotContain("SELECT ")
                .doesNotContain("Caused by");
    }
}
