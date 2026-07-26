package com.knowyourinterview.api.auth;

/**
 * Verifies a Google ID token (the JWT credential Google Identity Services hands back to the
 * frontend after "Sign in with Google") and returns the identity claims it's safe to trust.
 * Implementations are responsible for checking signature, issuer, audience and expiry —
 * callers should never decode the token themselves. See GoogleSignInVerifier for the real
 * (JWKS-backed) implementation; AuthServiceTest mocks this interface directly rather than
 * dealing with real signed tokens.
 */
public interface GoogleIdTokenVerifierPort {

    /**
     * @throws GoogleAuthNotConfiguredException if no GOOGLE_CLIENT_ID is configured
     * @throws InvalidCredentialsException if the token is missing, malformed, expired, has
     *         the wrong audience, or its email isn't verified by Google
     */
    GoogleUserInfo verify(String idTokenString);
}
