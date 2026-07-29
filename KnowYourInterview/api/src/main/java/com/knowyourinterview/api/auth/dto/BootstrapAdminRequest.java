package com.knowyourinterview.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Promotes an already-registered account to admin — see AuthService#bootstrapAdmin. The
 * account must already exist (register or sign in with Google first); this only flips the
 * flag, it doesn't create an account. */
public record BootstrapAdminRequest(@Email @NotBlank String email, @NotBlank String secret) {
}
