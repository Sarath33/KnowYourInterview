package com.knowyourinterview.api.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.user.User;

/** Field names match shared/types.ts `User` exactly (isAdmin, not admin). */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean isAdmin,
        // Drives the "confirm your email" banner and the disabled submit/unlock affordances
        // in the web app. Purely an affordance — the real gate is server-side (see
        // EmailVerificationGuard), which reads the database rather than trusting this.
        // Refreshed whenever a new session is issued, which is why the confirm screen
        // triggers a token refresh on success.
        boolean emailVerified,
        Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.isAdmin(),
                user.isEmailVerified(), user.getCreatedAt());
    }
}
