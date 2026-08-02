package com.knowyourinterview.api.profile;

/**
 * Raised when a Google-linked account tries to change its email from the profile page. Its
 * address is whatever Google asserts on sign-in, so letting it be edited here would either be
 * silently overwritten on the next Google login or leave the two out of sync — neither is
 * useful. Surfaced as a 409 (see ApiExceptionHandler). The message is written to be shown to
 * the user verbatim.
 */
public class GoogleManagedEmailException extends RuntimeException {
    public GoogleManagedEmailException() {
        super("Your email is managed by Google and can't be changed here");
    }
}
