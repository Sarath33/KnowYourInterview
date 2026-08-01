package com.knowyourinterview.api.functional;

import java.util.UUID;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.knowyourinterview.api.functional.support.FunctionalTestBase;
import com.knowyourinterview.api.functional.support.StubGoogleIdTokenVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FT-GOOG — "Sign in with Google" against real user rows. See {@code docs/09-test-plan.md} §7.3.
 *
 * <p>Token verification itself is stubbed (§6.4): what's under test here is
 * {@code AuthService#googleLogin}'s account-resolution logic, which is where the real risk lives —
 * getting it wrong either duplicates a user's account or, worse, hands one person's account to
 * another. Those outcomes are only visible against a real database with real unique constraints,
 * which is why they belong here rather than in {@code AuthServiceTest}.
 */
class GoogleSignInFunctionalIT extends FunctionalTestBase {

    private ResponseEntity<String> signInWithGoogle(String idToken) {
        return post("/api/v1/auth/google", null, new JSONObject().put("idToken", idToken));
    }

    @Test
    @DisplayName("FT-GOOG-01: a new Google user gets a password-less account")
    void newGoogleUserGetsPasswordlessAccount() {
        String email = uniqueEmail("google-new");
        String subject = "google-subject-" + UUID.randomUUID();

        ResponseEntity<String> response =
                signInWithGoogle(StubGoogleIdTokenVerifier.validToken(subject, email, "Google Newcomer"));

        assertThat(statusOf(response)).isEqualTo(200);
        JSONObject user = jsonOf(response).getJSONObject("user");
        assertThat(user.getString("email")).isEqualTo(email);
        assertThat(user.getString("displayName")).isEqualTo("Google Newcomer");
        assertThat(user.getBoolean("isAdmin")).isFalse();

        UUID userId = UUID.fromString(user.getString("id"));
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId))
                .as("a Google-only account must have no password hash to attack")
                .isNull();
        assertThat(jdbc.queryForObject("SELECT google_sub FROM users WHERE id = ?", String.class, userId))
                .isEqualTo(subject);
        assertThat(countRows("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-GOOG-02: an existing email/password account is linked, not duplicated")
    void existingAccountIsLinkedRatherThanDuplicated() {
        Actor existing = registerUser("Already Registered", uniqueEmail("google-link"));
        String subject = "google-subject-" + UUID.randomUUID();

        ResponseEntity<String> response =
                signInWithGoogle(StubGoogleIdTokenVerifier.validToken(subject, existing.email(), "Already Registered"));

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(UUID.fromString(jsonOf(response).getJSONObject("user").getString("id")))
                .as("signing in with Google must land on the same account, not a second one")
                .isEqualTo(existing.id());
        assertThat(countRows("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT google_sub FROM users WHERE id = ?", String.class, existing.id()))
                .isEqualTo(subject);

        // Linking must not have destroyed the original password.
        assertThat(statusOf(post("/api/v1/auth/login", null, loginBody(existing.email(), PASSWORD)))).isEqualTo(200);
    }

    @Test
    @DisplayName("FT-GOOG-03: a returning Google user logs into the same account")
    void returningGoogleUserReusesTheSameAccount() {
        String email = uniqueEmail("google-returning");
        String subject = "google-subject-" + UUID.randomUUID();
        String token = StubGoogleIdTokenVerifier.validToken(subject, email, "Returning User");

        UUID firstId = UUID.fromString(jsonOf(signInWithGoogle(token)).getJSONObject("user").getString("id"));
        UUID secondId = UUID.fromString(jsonOf(signInWithGoogle(token)).getJSONObject("user").getString("id"));

        assertThat(secondId).isEqualTo(firstId);
        assertThat(countRows("users")).isEqualTo(1);
    }

    @Test
    @DisplayName("FT-GOOG-03b: the Google subject wins over the email if they disagree")
    void googleSubjectTakesPrecedenceOverEmail() {
        String originalEmail = uniqueEmail("google-renamed");
        String subject = "google-subject-" + UUID.randomUUID();
        signInWithGoogle(StubGoogleIdTokenVerifier.validToken(subject, originalEmail, "Original Name"));

        // Same Google account, different email claim (a user who changed their Google address).
        // Resolution is by subject first, so this must not create a second account.
        ResponseEntity<String> response = signInWithGoogle(
                StubGoogleIdTokenVerifier.validToken(subject, uniqueEmail("google-changed"), "Changed Name"));

        assertThat(statusOf(response)).isEqualTo(200);
        assertThat(countRows("users")).isEqualTo(1);
        // The stored email is deliberately not updated — documenting, not asserting a preference.
        assertThat(jsonOf(response).getJSONObject("user").getString("email")).isEqualTo(originalEmail);
    }

    @Test
    @DisplayName("FT-GOOG-04: password login on a Google-only account is a clean 401, not a 500")
    void passwordLoginOnGoogleOnlyAccountIsRejected() {
        String email = uniqueEmail("google-nopassword");
        signInWithGoogle(StubGoogleIdTokenVerifier.validToken(
                "google-subject-" + UUID.randomUUID(), email, "No Password Here"));

        ResponseEntity<String> response = post("/api/v1/auth/login", null, loginBody(email, PASSWORD));

        // password_hash is null for these accounts; without the explicit guard in AuthService
        // this is a NullPointerException inside PasswordEncoder.matches and a 500.
        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(messageOf(response)).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("FT-GOOG-05: an unverifiable Google token is rejected and creates nothing")
    void invalidGoogleTokenIsRejected() {
        ResponseEntity<String> response = signInWithGoogle("not-a-real-google-id-token");

        assertThat(statusOf(response)).isEqualTo(401);
        assertThat(countRows("users")).isZero();
    }

    @Test
    @DisplayName("FT-GOOG-06: a blank idToken fails validation before anything else runs")
    void blankTokenFailsValidation() {
        ResponseEntity<String> response = post("/api/v1/auth/google", null, new JSONObject().put("idToken", ""));

        assertThat(statusOf(response)).isEqualTo(400);
        assertThat(jsonOf(response).getJSONObject("fieldErrors").keySet()).contains("idToken");
    }

    @Test
    @DisplayName("FT-GOOG-07: Google sign-in degrades to 503 when it isn't configured")
    void unconfiguredGoogleSignInReturns503() {
        ResponseEntity<String> response = signInWithGoogle(StubGoogleIdTokenVerifier.UNCONFIGURED_TOKEN);

        // Graceful degradation, same pattern as Razorpay and Sentry: a missing GOOGLE_CLIENT_ID
        // disables the feature rather than breaking startup or 500ing at call time.
        assertThat(statusOf(response)).isEqualTo(503);
        assertThat(countRows("users")).isZero();
    }
}
