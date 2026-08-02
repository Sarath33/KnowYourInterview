package com.knowyourinterview.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The address being confirmed plus the code that was emailed to it.
 * <p>
 * The email isn't redundant. A six-digit code isn't unique enough to identify a user on its own
 * — see EmailVerificationTokenRepository — so it's checked against one specific account's live
 * code, which is also what makes the per-code attempt limit mean anything. Passing the address
 * explicitly (rather than reading it from the session) additionally lets someone confirm from a
 * device they haven't signed in on, which is the common case when the code arrives on a phone.
 *
 * @param code exactly six digits. Validated here so an obviously malformed submission is a 400
 *             with a field error rather than a wasted guess against the attempt budget.
 */
public record VerifyEmailRequest(
        @Email @NotBlank String email,
        @NotBlank @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code from your email") String code) {
}
