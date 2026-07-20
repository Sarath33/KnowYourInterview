package com.knowyourinterview.api.auth.dto;

/** Matches shared/types.ts `AuthResponse`. */
public record AuthResponse(String accessToken, String refreshToken, UserResponse user) {
}
