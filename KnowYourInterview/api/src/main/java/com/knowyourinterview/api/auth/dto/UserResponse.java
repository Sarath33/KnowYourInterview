package com.knowyourinterview.api.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.user.User;

/** Field names match shared/types.ts `User` exactly (isAdmin, not admin). */
public record UserResponse(UUID id, String email, String displayName, boolean isAdmin, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(), user.getEmail(), user.getDisplayName(), user.isAdmin(), user.getCreatedAt());
    }
}
