package com.knowyourinterview.api.profile;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.auth.dto.MessageResponse;
import com.knowyourinterview.api.auth.dto.UserResponse;
import com.knowyourinterview.api.profile.dto.ChangeEmailRequest;
import com.knowyourinterview.api.profile.dto.ChangePasswordRequest;
import com.knowyourinterview.api.profile.dto.DeleteAccountRequest;
import com.knowyourinterview.api.profile.dto.PayoutAccountResponse;
import com.knowyourinterview.api.profile.dto.ProfileResponse;
import com.knowyourinterview.api.profile.dto.UpdateProfileRequest;
import com.knowyourinterview.api.profile.dto.UpsertPayoutAccountRequest;
import com.knowyourinterview.api.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Self-service account management for the signed-in user. Every endpoint acts on the caller's
 * own account (id comes from the JWT principal, never the request), and all require
 * authentication — they fall under SecurityConfig's anyRequest().authenticated().
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse getProfile(@AuthenticationPrincipal AuthenticatedUser user) {
        return profileService.getProfile(user.id());
    }

    @PatchMapping
    public UserResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpdateProfileRequest request) {
        return profileService.updateDisplayName(user.id(), request.displayName());
    }

    @PostMapping("/change-email")
    public MessageResponse changeEmail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChangeEmailRequest request) {
        return profileService.changeEmail(user.id(), request.newEmail());
    }

    @PostMapping("/change-password")
    public MessageResponse changePassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ChangePasswordRequest request) {
        return profileService.changePassword(user.id(), request.currentPassword(), request.newPassword());
    }

    @PutMapping("/payout-account")
    public PayoutAccountResponse upsertPayoutAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody UpsertPayoutAccountRequest request) {
        return profileService.upsertPayoutAccount(user.id(), request.accountHolderName(), request.upiVpa());
    }

    // Body is optional — a Google-only account deletes with no password, and may send no body
    // at all — so it's bound with required = false and null-checked before use.
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) DeleteAccountRequest request) {
        profileService.deleteAccount(user.id(), request == null ? null : request.password());
    }
}
