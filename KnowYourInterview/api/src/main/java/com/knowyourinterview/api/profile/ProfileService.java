package com.knowyourinterview.api.profile;

import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.auth.EmailAlreadyRegisteredException;
import com.knowyourinterview.api.auth.EmailVerificationService;
import com.knowyourinterview.api.auth.InvalidCredentialsException;
import com.knowyourinterview.api.auth.dto.MessageResponse;
import com.knowyourinterview.api.auth.dto.UserResponse;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.common.crypto.AesGcmEncryptor;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.payment.EntitlementRepository;
import com.knowyourinterview.api.payout.Payout;
import com.knowyourinterview.api.payout.PayoutRepository;
import com.knowyourinterview.api.profile.dto.PayoutAccountResponse;
import com.knowyourinterview.api.profile.dto.ProfileResponse;
import com.knowyourinterview.api.user.PayoutAccount;
import com.knowyourinterview.api.user.PayoutAccountRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

/**
 * Self-service account management for the signed-in user: the profile summary, and the edits
 * behind it — display name, email (with re-verification), password, payout destination, and
 * account deletion. Everything here operates on the caller's own account only; the userId is
 * always the authenticated principal's, never a path variable, so there's nothing to authorize
 * beyond "is signed in" (enforced by SecurityConfig).
 * <p>
 * Nothing in this class logs email/UPI/password values.
 */
@Service
public class ProfileService {

    private final UserRepository userRepository;
    private final PayoutAccountRepository payoutAccountRepository;
    private final PayoutRepository payoutRepository;
    private final ExperienceRepository experienceRepository;
    private final EntitlementRepository entitlementRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final AesGcmEncryptor encryptor;

    public ProfileService(
            UserRepository userRepository,
            PayoutAccountRepository payoutAccountRepository,
            PayoutRepository payoutRepository,
            ExperienceRepository experienceRepository,
            EntitlementRepository entitlementRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            AesGcmEncryptor encryptor) {
        this.userRepository = userRepository;
        this.payoutAccountRepository = payoutAccountRepository;
        this.payoutRepository = payoutRepository;
        this.experienceRepository = experienceRepository;
        this.entitlementRepository = entitlementRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.encryptor = encryptor;
    }

    @Transactional(readOnly = true)
    public ProfileResponse getProfile(UUID userId) {
        User user = requireUser(userId);

        PayoutAccountResponse payoutAccount = payoutAccountRepository.findByUserId(userId)
                .map(this::toResponse)
                .orElse(null);

        long totalEarnedPaise =
                payoutRepository.sumAmountByContributorIdAndStatus(userId, Payout.Status.PAID);
        long pendingPayoutPaise = payoutRepository.sumAmountByContributorIdAndStatusIn(
                userId, List.of(Payout.Status.PENDING, Payout.Status.PROCESSING));
        long submissionCount = experienceRepository.countByContributorId(userId);
        long purchaseCount = entitlementRepository.countByUserId(userId);

        return new ProfileResponse(
                UserResponse.from(user),
                user.getPasswordHash() != null,
                user.getGoogleSub() != null,
                payoutAccount,
                totalEarnedPaise,
                pendingPayoutPaise,
                submissionCount,
                purchaseCount);
    }

    @Transactional
    public UserResponse updateDisplayName(UUID userId, String displayName) {
        User user = requireUser(userId);
        user.rename(displayName);
        userRepository.save(user);
        return UserResponse.from(user);
    }

    /**
     * Points the account at a new address and re-runs confirmation against it. The code goes to
     * the NEW inbox, not the old one — that's the proof of control being re-established. Until
     * it's confirmed the account is back to unverified, so anything gated on emailVerified is
     * blocked, exactly as for a fresh registration.
     */
    @Transactional
    public MessageResponse changeEmail(UUID userId, String newEmail) {
        User user = requireUser(userId);
        if (user.getGoogleSub() != null) {
            throw new GoogleManagedEmailException();
        }
        // No-op if it's already their address — nothing to confirm, and treating it as a
        // duplicate below would be a confusing error for a harmless request.
        if (user.getEmail().equalsIgnoreCase(newEmail)) {
            return new MessageResponse("That's already the email address on your account.");
        }
        if (userRepository.existsByEmailIgnoreCase(newEmail)) {
            throw new EmailAlreadyRegisteredException(newEmail);
        }
        user.changeEmailPendingReverification(newEmail);
        userRepository.save(user);
        // Sends the confirmation code to the new address (issueAndSend reads user.getEmail(),
        // which now points at newEmail, and only sends because emailVerified is now false).
        emailVerificationService.issueAndSend(user);
        return new MessageResponse(
                "We've sent a confirmation code to your new email. Enter it to finish the change.");
    }

    /**
     * A user with a password must prove the current one; a Google-only account (no password on
     * file) is setting its first password and has nothing to prove — the valid JWT is the proof.
     */
    @Transactional
    public MessageResponse changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = requireUser(userId);
        if (user.getPasswordHash() != null) {
            if (currentPassword == null || currentPassword.isBlank()
                    || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new InvalidCredentialsException();
            }
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        return new MessageResponse("Password updated.");
    }

    /**
     * Full-replace upsert of the single payout row for this user (user_id is UNIQUE). The VPA is
     * normalized (trimmed + lowercased — VPAs are case-insensitive) and stored encrypted at rest
     * via {@link AesGcmEncryptor}; the account holder name is trimmed but keeps its original case.
     * The returned response carries the plaintext VPA so the API contract is unchanged.
     */
    @Transactional
    public PayoutAccountResponse upsertPayoutAccount(UUID userId, String accountHolderName, String upiVpa) {
        requireUser(userId);
        String normalizedName = accountHolderName == null ? null : accountHolderName.trim();
        String normalizedVpa = upiVpa == null ? null : upiVpa.trim().toLowerCase();
        String encryptedVpa = encryptor.encrypt(normalizedVpa);

        PayoutAccount account = payoutAccountRepository.findByUserId(userId).orElse(null);
        if (account == null) {
            account = new PayoutAccount(UUID.randomUUID(), userId, normalizedName, encryptedVpa);
        } else {
            account.update(normalizedName, encryptedVpa);
        }
        payoutAccountRepository.save(account);
        // Build the response from the known plaintext rather than re-decrypting what we just stored.
        return new PayoutAccountResponse(normalizedName, normalizedVpa);
    }

    /** Maps a stored payout row to its response, decrypting the at-rest VPA back to plaintext. */
    private PayoutAccountResponse toResponse(PayoutAccount account) {
        return new PayoutAccountResponse(account.getAccountHolderName(), encryptor.decrypt(account.getUpiVpa()));
    }

    /**
     * Self-delete. A password-holder must confirm with it; a Google-only account can't (nothing
     * to confirm with) so the valid session is enough. The row is anonymized rather than removed
     * (see User#anonymizeForDeletion) because experiences/payouts/purchases reference it.
     */
    @Transactional
    public void deleteAccount(UUID userId, String password) {
        User user = requireUser(userId);
        if (user.getPasswordHash() != null) {
            if (password == null || password.isBlank()
                    || !passwordEncoder.matches(password, user.getPasswordHash())) {
                throw new InvalidCredentialsException();
            }
        }
        user.anonymizeForDeletion();
        userRepository.save(user);
        // Session revocation is best-effort: refresh tokens live in Redis keyed by jti
        // (refresh:<jti> -> userId) with no reverse per-user index, so there's no single call
        // to drop all of this user's sessions. Instead the session is cut off at the next
        // rotation — AuthService.refresh (and login) reject any user whose deletedAt is set, so
        // the outstanding refresh token can't be exchanged and the short-lived access token
        // simply expires. If a live per-user revoke is ever needed, add a userId->jti set here.
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Account not found"));
    }
}
