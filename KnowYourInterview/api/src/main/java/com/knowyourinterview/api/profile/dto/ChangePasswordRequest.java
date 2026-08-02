package com.knowyourinterview.api.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * currentPassword is intentionally unconstrained here: a Google-only account (no password on
 * file yet) sets its first password without one, so requiring it at the bean-validation layer
 * would block that path. Whether it's actually needed — and correct — is decided in
 * ProfileService against the account's real state.
 */
public record ChangePasswordRequest(
        String currentPassword,
        @NotBlank @Size(min = 8, max = 128) String newPassword) {
}
