package com.knowyourinterview.api.auth;

/**
 * Thrown when an account with an unconfirmed email address attempts something that requires a
 * reachable one. Mapped to 403 by ApiExceptionHandler.
 * <p>
 * 403 rather than 401: the caller is authenticated perfectly well, they're just not permitted
 * this particular action yet. A 401 would tell the web client's api layer that the session is
 * dead and send it into a token refresh, then a logout — exactly the wrong response to
 * "confirm your email first".
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
