package com.knowyourinterview.api.functional.support;

import com.knowyourinterview.api.auth.GoogleAuthNotConfiguredException;
import com.knowyourinterview.api.auth.GoogleIdTokenVerifierPort;
import com.knowyourinterview.api.auth.GoogleUserInfo;
import com.knowyourinterview.api.auth.InvalidCredentialsException;

/**
 * Deterministic stand-in for {@link com.knowyourinterview.api.auth.GoogleSignInVerifier}, so the
 * functional suite can exercise all three branches of {@code AuthService#googleLogin} (new
 * account / link to an existing email / returning user) against real database rows without an
 * outbound call to Google's JWKS endpoint.
 *
 * <p>Token grammar, chosen so a test reads as its own fixture:
 * <ul>
 *   <li>{@code valid:<subject>:<email>:<displayName>} — verifies successfully.</li>
 *   <li>{@code unconfigured} — behaves as if {@code GOOGLE_CLIENT_ID} were blank (503 path).</li>
 *   <li>anything else — {@link InvalidCredentialsException}, i.e. the 401 path.</li>
 * </ul>
 *
 * <p>Stateless on purpose: no per-test setup, no ordering coupling, nothing to reset between
 * tests. What this deliberately does <em>not</em> cover is signature/issuer/audience/expiry and
 * the {@code email_verified} check — that logic lives in {@code GoogleSignInVerifier} and is the
 * one boundary the functional suite stubs rather than exercises. See {@code docs/09-test-plan.md}
 * §6.4 and gap G3.
 */
public class StubGoogleIdTokenVerifier implements GoogleIdTokenVerifierPort {

    public static final String UNCONFIGURED_TOKEN = "unconfigured";

    /** Builds a token this stub will accept. */
    public static String validToken(String subject, String email, String displayName) {
        return "valid:" + subject + ":" + email + ":" + displayName;
    }

    @Override
    public GoogleUserInfo verify(String idTokenString) {
        if (UNCONFIGURED_TOKEN.equals(idTokenString)) {
            throw new GoogleAuthNotConfiguredException();
        }
        if (idTokenString == null || !idTokenString.startsWith("valid:")) {
            throw new InvalidCredentialsException();
        }
        String[] parts = idTokenString.split(":", 4);
        if (parts.length != 4) {
            throw new InvalidCredentialsException();
        }
        return new GoogleUserInfo(parts[1], parts[2], parts[3]);
    }
}
