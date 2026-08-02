package com.knowyourinterview.api.functional;

import java.util.List;
import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.Payloads;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-AUTHZ — a systematic sweep of who can reach what. See {@code docs/09-test-plan.md} §7.4.
 *
 * <p>This is the highest-value file in the suite. Authorization bugs are quiet: nothing breaks,
 * nothing errors, the wrong person just sees something. They also cluster around exactly the
 * things a mocked slice test can't see — the ordering of rules in {@code SecurityConfig}, Ant
 * pattern overlap, and the difference between "not signed in" (401), "signed in but not allowed"
 * (403) and "not allowed to know this exists" (404).
 *
 * <p>Routes are swept as data rather than one test method per endpoint, so a newly added route
 * gets covered by adding one line — and the assertion messages name the offending route.
 */
class AuthorizationMatrixFunctionalIT extends FunctionalTestBase {

    /** One route in the sweep. {@code body} is null for routes that take none. */
    private record Route(HttpMethod method, String path, Object body) {
        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private List<Route> authenticatedRoutes(UUID experienceId, UUID roundId, UUID proofId) {
        return List.of(
                new Route(HttpMethod.GET, "/api/v1/experiences/mine", null),
                new Route(HttpMethod.POST, "/api/v1/experiences", Payloads.experience()),
                new Route(HttpMethod.PUT, "/api/v1/experiences/" + experienceId, Payloads.experience()),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/rounds", Payloads.round()),
                new Route(HttpMethod.PUT,
                        "/api/v1/experiences/" + experienceId + "/rounds/" + roundId, Payloads.round()),
                new Route(HttpMethod.DELETE,
                        "/api/v1/experiences/" + experienceId + "/rounds/" + roundId, null),
                new Route(HttpMethod.GET,
                        "/api/v1/experiences/" + experienceId + "/proof/" + proofId, null),
                new Route(HttpMethod.DELETE,
                        "/api/v1/experiences/" + experienceId + "/proof/" + proofId, null),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/submit", null),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/unpublish", null),
                new Route(HttpMethod.DELETE, "/api/v1/experiences/" + experienceId, null),
                new Route(HttpMethod.GET, "/api/v1/experiences/" + experienceId + "/history", null),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/purchase", null),
                new Route(HttpMethod.POST, "/api/v1/purchases/confirm", new JSONObject()
                        .put("razorpayOrderId", "order_x").put("razorpayPaymentId", "pay_x")
                        .put("razorpaySignature", "sig_x")),
                new Route(HttpMethod.GET, "/api/v1/purchases/mine", null),
                new Route(HttpMethod.GET, "/api/v1/payouts/mine", null));
    }

    private List<Route> adminRoutes(UUID experienceId, UUID payoutId) {
        return List.of(
                new Route(HttpMethod.GET, "/api/v1/admin/experiences", null),
                new Route(HttpMethod.GET, "/api/v1/admin/experiences/" + experienceId, null),
                new Route(HttpMethod.POST, "/api/v1/admin/experiences/" + experienceId + "/approve", null),
                new Route(HttpMethod.POST, "/api/v1/admin/experiences/" + experienceId + "/reject",
                        new JSONObject().put("reason", "no")),
                new Route(HttpMethod.POST, "/api/v1/admin/experiences/" + experienceId + "/request-correction",
                        new JSONObject().put("notes", "please fix")),
                new Route(HttpMethod.GET, "/api/v1/admin/payouts", null),
                new Route(HttpMethod.POST, "/api/v1/admin/payouts/" + payoutId + "/mark-paid",
                        new JSONObject().put("reference", "UPI-1")));
    }

    // --- Anonymous --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTHZ-01: every authenticated route rejects an anonymous caller with 401")
    void anonymousIsRejectedFromEveryAuthenticatedRoute() {
        Actor owner = registerUser();
        UUID experienceId = createDraft(owner);
        UUID roundId = addRound(owner, experienceId);
        UUID proofId = uploadProof(owner, experienceId);

        for (Route route : authenticatedRoutes(experienceId, roundId, proofId)) {
            ResponseEntity<String> response = exchange(route.method(), route.path(), headers(null), route.body());

            // 401, specifically: 403 would mean the request got past authentication, and 500
            // would mean a controller ran with a null principal (the Phase 3 routing bug).
            assertThat(statusOf(response))
                    .as("anonymous %s should be 401", route)
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("FT-AUTHZ-02: /experiences/mine is not swallowed by the public /experiences/* rule")
    void mineIsNotTreatedAsAPublicExperienceLookup() {
        registerUser();

        ResponseEntity<String> response = getAnonymously("/api/v1/experiences/mine");

        // "mine" is a single path segment and would otherwise match the permitAll
        // /api/v1/experiences/* browse rule, reaching a controller method that assumes an
        // authenticated user. SecurityConfig lists it first on purpose; this pins that ordering.
        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(response.getBody() == null || !response.getBody().contains("\"items\"")).isTrue();
    }

    @Test
    @DisplayName("FT-AUTHZ-04: every admin route rejects an anonymous caller with 401")
    void anonymousIsRejectedFromAdminRoutes() {
        for (Route route : adminRoutes(UUID.randomUUID(), UUID.randomUUID())) {
            assertThat(statusOf(exchange(route.method(), route.path(), headers(null), route.body())))
                    .as("anonymous %s should be 401", route)
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("FT-AUTHZ-05: genuinely public routes stay reachable without a token")
    void publicRoutesStayPublic() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        UUID published = publishedExperience(contributor, admin);

        assertThat(statusOf(getAnonymously("/api/v1/health"))).isEqualTo(200);
        assertThat(statusOf(getAnonymously("/actuator/health"))).isEqualTo(200);
        assertThat(statusOf(getAnonymously("/api/v1/experiences"))).isEqualTo(200);
        assertThat(statusOf(getAnonymously("/api/v1/experiences/" + published))).isEqualTo(200);
        // The webhook has no JWT by design — its signature check is the authentication, so an
        // unsigned call must fail on the signature (401 from the controller), not on the filter.
        assertThat(statusOf(exchange(HttpMethod.POST, "/api/v1/payments/webhook", headers(null),
                Payloads.paymentCaptured("order_x", "pay_x")))).isEqualTo(401);
    }

    // --- Non-admin --------------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTHZ-03: a signed-in non-admin gets 403 from every admin route")
    void nonAdminIsForbiddenFromAdminRoutes() {
        Actor contributor = registerUser();
        Actor admin = registerAdmin();
        Actor outsider = registerUser();
        UUID experienceId = publishedExperience(contributor, admin);
        UUID payoutId = UUID.fromString(
                jdbc.queryForObject("SELECT id::text FROM payouts LIMIT 1", String.class));
        // One APPROVED entry already exists from the fixture's own approval — the point below
        // is that the outsider's attempts add nothing to it.
        long reviewLogsBefore = countRows("review_logs");

        for (Route route : adminRoutes(experienceId, payoutId)) {
            ResponseEntity<String> response =
                    exchange(route.method(), route.path(), headers(outsider.accessToken()), route.body());

            assertThat(statusOf(response))
                    .as("non-admin %s should be 403", route)
                    .isEqualTo(403);
        }
        // Nothing was mutated on the way through.
        assertThat(countRows("payouts", "status = 'PAID'")).isZero();
        assertThat(countRows("review_logs")).isEqualTo(reviewLogsBefore);
        assertThat(statusOfExperience(experienceId)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("FT-AUTHZ-06: a stranger cannot write to someone else's experience")
    void crossUserWritesAreForbidden() {
        Actor owner = registerUser();
        Actor stranger = registerUser();
        UUID experienceId = createDraft(owner);
        UUID roundId = addRound(owner, experienceId);
        UUID proofId = uploadProof(owner, experienceId);

        List<Route> writes = List.of(
                new Route(HttpMethod.PUT, "/api/v1/experiences/" + experienceId,
                        Payloads.experience().put("company", "Hijacked")),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/rounds", Payloads.round()),
                new Route(HttpMethod.PUT,
                        "/api/v1/experiences/" + experienceId + "/rounds/" + roundId, Payloads.round()),
                new Route(HttpMethod.DELETE,
                        "/api/v1/experiences/" + experienceId + "/rounds/" + roundId, null),
                new Route(HttpMethod.DELETE,
                        "/api/v1/experiences/" + experienceId + "/proof/" + proofId, null),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/submit", null),
                new Route(HttpMethod.POST, "/api/v1/experiences/" + experienceId + "/unpublish", null),
                new Route(HttpMethod.DELETE, "/api/v1/experiences/" + experienceId, null));

        for (Route route : writes) {
            assertThat(statusOf(exchange(route.method(), route.path(), headers(stranger.accessToken()), route.body())))
                    .as("stranger %s should be 403", route)
                    .isEqualTo(403);
        }

        // And the owner's data is exactly as it was.
        JSONObject stillOwned = jsonOf(get("/api/v1/experiences/" + experienceId, owner)).getJSONObject("full");
        assertThat(stillOwned.getString("company")).isEqualTo("Acme Corp");
        assertThat(stillOwned.getJSONArray("rounds").length()).isEqualTo(1);
        assertThat(stillOwned.getJSONArray("proofDocuments").length()).isEqualTo(1);
        assertThat(stillOwned.getString("status")).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("FT-AUTHZ-07: a stranger cannot read someone else's history or proof documents")
    void crossUserReadsAreForbidden() {
        Actor owner = registerUser();
        Actor stranger = registerUser();
        UUID experienceId = createDraft(owner);
        UUID proofId = uploadProof(owner, experienceId);
        put("/api/v1/experiences/" + experienceId, owner, Payloads.experience().put("company", "Changed"));

        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/history", stranger))).isEqualTo(403);
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, stranger)))
                .isEqualTo(403);
    }

    @Test
    @DisplayName("FT-AUTHZ-08: an admin can reach what the design says an admin should")
    void adminOverridesWorkWhereIntended() {
        Actor owner = registerUser();
        Actor admin = registerAdmin();
        UUID experienceId = createDraft(owner);
        UUID proofId = uploadProof(owner, experienceId);
        put("/api/v1/experiences/" + experienceId, owner, Payloads.experience().put("company", "Edited Once"));

        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/history", admin))).isEqualTo(200);
        assertThat(statusOf(get("/api/v1/experiences/" + experienceId + "/proof/" + proofId, admin))).isEqualTo(200);
        // Direct edit is part of the correction-requested flow.
        assertThat(statusOf(put("/api/v1/experiences/" + experienceId, admin,
                Payloads.experience().put("company", "Admin Corrected")))).isEqualTo(200);

        // But an admin is not the owner: owner-only routes stay owner-only even for them.
        assertThat(statusOf(post("/api/v1/experiences/" + experienceId + "/rounds", admin, Payloads.round())))
                .as("addRound is getOwned, not getOwnedOrAdmin — an admin is not the contributor")
                .isEqualTo(403);
        assertThat(statusOf(delete("/api/v1/experiences/" + experienceId, admin))).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-AUTHZ-09: admin cannot be self-granted with a wrong bootstrap secret")
    void bootstrapSecretCannotBeGuessedIntoAdmin() {
        Actor actor = registerUser();

        ResponseEntity<String> response = post("/api/v1/auth/bootstrap-admin", null,
                new JSONObject().put("email", actor.email()).put("secret", "not-the-real-secret"));

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT is_admin FROM users WHERE id = ?", Boolean.class, actor.id()))
                .isFalse();
        // A token issued before the failed attempt is still a plain user token.
        assertThat(statusOf(get("/api/v1/admin/experiences", login(actor.email(), PASSWORD)))).isEqualTo(403);
    }

    @Test
    @DisplayName("FT-AUTHZ-09b: bootstrap-admin on an unknown email creates no account")
    void bootstrapAdminDoesNotCreateAccounts() {
        ResponseEntity<String> response = post("/api/v1/auth/bootstrap-admin", null,
                new JSONObject().put("email", uniqueEmail("never-registered"))
                        .put("secret", ADMIN_BOOTSTRAP_SECRET));

        assertThat(statusOf(response)).isEqualTo(404);
        assertThat(countRows("users")).isZero();
    }

    // --- Actuator ---------------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTHZ-10: actuator health detail is admin-only")
    void actuatorHealthDetailIsAdminOnly() {
        Actor admin = registerAdmin();
        Actor plainUser = registerUser();

        ResponseEntity<String> anonymous = getAnonymously("/actuator/health");
        assertThat(statusOf(anonymous)).isEqualTo(200);
        assertThat(jsonOf(anonymous).getString("status")).isEqualTo("UP");
        assertThat(jsonOf(anonymous).has("components"))
                .as("infrastructure detail must not be visible to the internet")
                .isFalse();

        assertThat(jsonOf(get("/actuator/health", plainUser)).has("components")).isFalse();

        JSONObject asAdmin = jsonOf(get("/actuator/health", admin));
        assertThat(asAdmin.has("components")).isTrue();
        assertThat(asAdmin.getJSONObject("components").keySet()).contains("db", "redis");
        assertThat(asAdmin.getJSONObject("components").getJSONObject("db").getString("status")).isEqualTo("UP");
        assertThat(asAdmin.getJSONObject("components").getJSONObject("redis").getString("status")).isEqualTo("UP");
    }

    @Test
    @DisplayName("FT-AUTHZ-11: no actuator endpoint beyond health/info is reachable, even for an admin")
    void otherActuatorEndpointsAreNotExposed() {
        Actor admin = registerAdmin();

        // SecurityConfig permitAlls /actuator/**, so exposure is the only thing keeping these
        // closed. If someone widens management.endpoints.web.exposure.include, this catches it —
        // /actuator/env alone would leak JWT_SECRET and the Razorpay keys.
        for (String endpoint : List.of("env", "beans", "configprops", "loggers", "heapdump", "threaddump", "metrics")) {
            assertThat(statusOf(get("/actuator/" + endpoint, admin)))
                    .as("/actuator/%s must not be exposed", endpoint)
                    .isEqualTo(404);
            assertThat(statusOf(getAnonymously("/actuator/" + endpoint)))
                    .as("/actuator/%s must not be exposed anonymously", endpoint)
                    .isEqualTo(404);
        }
    }

    // --- Existence leaks ---------------------------------------------------------------------

    @Test
    @DisplayName("FT-AUTHZ-12: an unpublished experience is a 404 to a stranger, not a 403")
    void unpublishedExperienceDoesNotLeakItsExistence() {
        Actor owner = registerUser();
        Actor stranger = registerUser();
        UUID draftId = createDraft(owner);
        UUID neverExisted = UUID.randomUUID();

        // A 403 would confirm "there is something here" — for a draft naming a real company and
        // a real interview, that alone is a disclosure.
        assertThat(statusOf(get("/api/v1/experiences/" + draftId, stranger))).isEqualTo(404);
        assertThat(statusOf(getAnonymously("/api/v1/experiences/" + draftId))).isEqualTo(404);
        assertThat(statusOf(get("/api/v1/experiences/" + neverExisted, stranger))).isEqualTo(404);
        // Indistinguishable from a random id, which is the point.
        assertThat(messageOf(get("/api/v1/experiences/" + draftId, stranger)))
                .isEqualTo(messageOf(get("/api/v1/experiences/" + neverExisted, stranger)));
    }
}
