package com.knowyourinterview.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Addressed by email rather than taken from the caller's session, mirroring
 * ForgotPasswordRequest. That keeps the endpoint usable from the login screen (where there's
 * no session yet) as well as from the signed-in banner, and it avoids the awkward shape of an
 * authenticated route sitting under the blanket-permitAll /api/v1/auth/** rule in
 * SecurityConfig. The response is identical for every address, so passing one in leaks
 * nothing.
 */
public record ResendVerificationRequest(@Email @NotBlank String email) {
}
