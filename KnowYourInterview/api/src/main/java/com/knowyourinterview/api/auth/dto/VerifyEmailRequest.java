package com.knowyourinterview.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The raw token from a confirmation link's ?token= query param. */
public record VerifyEmailRequest(@NotBlank String token) {
}
