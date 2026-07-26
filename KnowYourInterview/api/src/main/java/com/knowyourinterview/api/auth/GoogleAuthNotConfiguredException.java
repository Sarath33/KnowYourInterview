package com.knowyourinterview.api.auth;

/**
 * Thrown when POST /api/v1/auth/google is called but GOOGLE_CLIENT_ID isn't set — same
 * graceful-degradation pattern as Razorpay/Sentry (see application.yml), just surfaced as an
 * explicit error here since a client hitting a "Sign in with Google" button deserves a clear
 * signal rather than a confusing generic failure. Maps to 503 in ApiExceptionHandler.
 */
public class GoogleAuthNotConfiguredException extends RuntimeException {
    public GoogleAuthNotConfiguredException() {
        super("Google Sign-In is not configured on this server");
    }
}
