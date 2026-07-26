package com.knowyourinterview.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The credential Google Identity Services' JS SDK hands back on the frontend after
 * "Sign in with Google" — a signed JWT, verified server-side in GoogleSignInVerifier. */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
