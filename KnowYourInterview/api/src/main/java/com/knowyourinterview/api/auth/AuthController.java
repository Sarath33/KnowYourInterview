package com.knowyourinterview.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.auth.dto.BootstrapAdminRequest;
import com.knowyourinterview.api.auth.dto.ForgotPasswordRequest;
import com.knowyourinterview.api.auth.dto.GoogleLoginRequest;
import com.knowyourinterview.api.auth.dto.LoginRequest;
import com.knowyourinterview.api.auth.dto.LogoutRequest;
import com.knowyourinterview.api.auth.dto.MessageResponse;
import com.knowyourinterview.api.auth.dto.RefreshRequest;
import com.knowyourinterview.api.auth.dto.RegisterRequest;
import com.knowyourinterview.api.auth.dto.ResendVerificationRequest;
import com.knowyourinterview.api.auth.dto.ResetPasswordRequest;
import com.knowyourinterview.api.auth.dto.VerifyEmailRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    public AuthController(AuthService authService, EmailVerificationService emailVerificationService) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.password(), request.displayName());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    // Alongside, not instead of, email/password — sign-up-or-login-in-one-step, same as
    // register/login above return an AuthResponse either way. See AuthService.googleLogin.
    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.googleLogin(request.idToken());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
        // Deliberately generic — doesn't reveal whether the email is registered.
        return ResponseEntity.ok(new MessageResponse(
                "If an account exists for that email, a reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(new MessageResponse("Password updated."));
    }

    /**
     * Checks a confirmation code. Public: the person typing it often isn't signed in on the
     * device that received it, and the code plus the address is the proof — requiring a session
     * on top would lock out the case this exists to serve.
     */
    @PostMapping("/verify-email")
    public ResponseEntity<MessageResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.email(), request.code());
        return ResponseEntity.ok(new MessageResponse("Email confirmed."));
    }

    /**
     * Sends a fresh confirmation code, invalidating any previous one. Generic response
     * regardless of whether the address exists or is already confirmed — same
     * no-enumeration posture as forgot-password. Rate-limited harder than the rest of
     * /auth/** (see RateLimitingFilter) because the abuse here lands in someone else's inbox,
     * and because each resend issues a fresh guess budget.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<MessageResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request) {
        emailVerificationService.resend(request.email());
        return ResponseEntity.ok(new MessageResponse(
                "If that account exists and isn't confirmed yet, a new link is on its way."));
    }

    // Secret-gated, not JWT-gated — see AuthService#bootstrapAdmin for why (no admin
    // exists yet to authorize this the first time it's used on a fresh environment).
    // 503 if ADMIN_BOOTSTRAP_SECRET isn't set; permitAll at the security-filter level like
    // the rest of /api/v1/auth/**, but rate-limited (see RateLimitingFilter).
    @PostMapping("/bootstrap-admin")
    public ResponseEntity<MessageResponse> bootstrapAdmin(@Valid @RequestBody BootstrapAdminRequest request) {
        authService.bootstrapAdmin(request.email(), request.secret());
        return ResponseEntity.ok(new MessageResponse(request.email() + " is now an admin."));
    }
}
