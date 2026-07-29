package com.knowyourinterview.api.auth;

/**
 * Thrown when POST /api/v1/auth/bootstrap-admin is called but ADMIN_BOOTSTRAP_SECRET isn't
 * set — same graceful-degradation pattern as Google/Razorpay/Sentry (see application.yml).
 * Without this, the only way to create a first admin is a direct database update; setting
 * the secret opts an environment into this endpoint instead. Maps to 503 in
 * ApiExceptionHandler.
 */
public class AdminBootstrapNotConfiguredException extends RuntimeException {
    public AdminBootstrapNotConfiguredException() {
        super("Admin bootstrap is not configured on this server");
    }
}
