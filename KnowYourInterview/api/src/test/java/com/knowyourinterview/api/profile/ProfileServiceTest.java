package com.knowyourinterview.api.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.knowyourinterview.api.auth.EmailAlreadyRegisteredException;
import com.knowyourinterview.api.auth.EmailVerificationService;
import com.knowyourinterview.api.auth.InvalidCredentialsException;
import com.knowyourinterview.api.auth.dto.UserResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for ProfileService — all collaborators mocked, no Spring context/DB/Redis.
 * Mirrors AuthServiceTest's style.
 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PayoutAccountRepository payoutAccountRepository;
    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailVerificationService emailVerificationService;

    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = new ProfileService(
                userRepository,
                payoutAccountRepository,
                payoutRepository,
                experienceRepository,
                entitlementRepository,
                passwordEncoder,
                emailVerificationService);
    }

    private User passwordUser() {
        return new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
    }

    private User googleUser() {
        return User.forGoogleSignup(UUID.randomUUID(), "jane@example.com", "Jane", "google-sub-1");
    }

    // --- getProfile ---

    @Test
    void getProfileAssemblesUserCredentialsPayoutAndCounters() {
        User user = passwordUser();
        UUID id = user.getId();
        PayoutAccount account = new PayoutAccount(UUID.randomUUID(), id, "Jane Doe", "jane@upi");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(payoutAccountRepository.findByUserId(id)).thenReturn(Optional.of(account));
        when(payoutRepository.sumAmountByContributorIdAndStatus(id, Payout.Status.PAID)).thenReturn(5000L);
        when(payoutRepository.sumAmountByContributorIdAndStatusIn(eq(id), anyList())).thenReturn(1500L);
        when(experienceRepository.countByContributorId(id)).thenReturn(3L);
        when(entitlementRepository.countByUserId(id)).thenReturn(2L);

        ProfileResponse response = profileService.getProfile(id);

        assertThat(response.user().email()).isEqualTo("jane@example.com");
        assertThat(response.hasPassword()).isTrue();
        assertThat(response.hasGoogle()).isFalse();
        assertThat(response.payoutAccount()).isNotNull();
        assertThat(response.payoutAccount().accountHolderName()).isEqualTo("Jane Doe");
        assertThat(response.payoutAccount().upiVpa()).isEqualTo("jane@upi");
        assertThat(response.totalEarnedPaise()).isEqualTo(5000L);
        assertThat(response.pendingPayoutPaise()).isEqualTo(1500L);
        assertThat(response.submissionCount()).isEqualTo(3L);
        assertThat(response.purchaseCount()).isEqualTo(2L);
    }

    @Test
    void getProfileReturnsNullPayoutAccountWhenNoneOnFileAndReflectsGoogleOnlyCredentials() {
        User user = googleUser();
        UUID id = user.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(payoutAccountRepository.findByUserId(id)).thenReturn(Optional.empty());

        ProfileResponse response = profileService.getProfile(id);

        assertThat(response.payoutAccount()).isNull();
        assertThat(response.hasPassword()).isFalse();
        assertThat(response.hasGoogle()).isTrue();
    }

    // --- updateDisplayName ---

    @Test
    void updateDisplayNameRenamesAndReturnsUpdatedUser() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        UserResponse response = profileService.updateDisplayName(user.getId(), "Jane Q. Public");

        assertThat(response.displayName()).isEqualTo("Jane Q. Public");
        assertThat(user.getDisplayName()).isEqualTo("Jane Q. Public");
        verify(userRepository).save(user);
    }

    // --- changeEmail ---

    @Test
    void changeEmailUpdatesAddressResetsVerificationAndReissuesConfirmation() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);

        profileService.changeEmail(user.getId(), "new@example.com");

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.isEmailVerified()).isFalse();
        verify(userRepository).save(user);
        // Confirmation goes to the NEW address (issueAndSend reads the just-updated user).
        verify(emailVerificationService).issueAndSend(user);
    }

    @Test
    void changeEmailRejectsAnAddressAlreadyRegisteredToSomeoneElse() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> profileService.changeEmail(user.getId(), "taken@example.com"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any());
        verify(emailVerificationService, never()).issueAndSend(any());
    }

    @Test
    void changeEmailRejectsGoogleManagedAccount() {
        User user = googleUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> profileService.changeEmail(user.getId(), "new@example.com"))
                .isInstanceOf(GoogleManagedEmailException.class);

        verify(userRepository, never()).save(any());
        verify(emailVerificationService, never()).issueAndSend(any());
    }

    @Test
    void changeEmailIsANoOpWhenTheAddressIsUnchanged() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        profileService.changeEmail(user.getId(), "JANE@example.com");

        // Unchanged (case-insensitive) — nothing saved, no re-verification triggered.
        assertThat(user.isEmailVerified()).isFalse();
        verify(userRepository, never()).existsByEmailIgnoreCase(any());
        verify(userRepository, never()).save(any());
        verify(emailVerificationService, never()).issueAndSend(any());
    }

    // --- changePassword ---

    @Test
    void changePasswordRequiresAndVerifiesCurrentPasswordWhenOneIsSet() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-pw", "hashed-pw")).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        profileService.changePassword(user.getId(), "current-pw", "new-password");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
    }

    @Test
    void changePasswordRejectsAWrongCurrentPassword() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed-pw")).thenReturn(false);

        assertThatThrownBy(() -> profileService.changePassword(user.getId(), "wrong", "new-password"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getPasswordHash()).isEqualTo("hashed-pw");
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePasswordAllowsGoogleOnlyAccountToSetFirstPasswordWithoutCurrentOne() {
        User user = googleUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        profileService.changePassword(user.getId(), null, "new-password");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
    }

    // --- upsertPayoutAccount ---

    @Test
    void upsertPayoutAccountCreatesRowWhenNoneExists() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(payoutAccountRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        PayoutAccountResponse response =
                profileService.upsertPayoutAccount(user.getId(), "Jane Doe", "jane@upi");

        assertThat(response.accountHolderName()).isEqualTo("Jane Doe");
        assertThat(response.upiVpa()).isEqualTo("jane@upi");

        ArgumentCaptor<PayoutAccount> captor = ArgumentCaptor.forClass(PayoutAccount.class);
        verify(payoutAccountRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(captor.getValue().getAccountHolderName()).isEqualTo("Jane Doe");
        assertThat(captor.getValue().getUpiVpa()).isEqualTo("jane@upi");
    }

    @Test
    void upsertPayoutAccountUpdatesExistingRowInPlace() {
        User user = passwordUser();
        PayoutAccount existing = new PayoutAccount(UUID.randomUUID(), user.getId(), "Old Name", "old@upi");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(payoutAccountRepository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        PayoutAccountResponse response =
                profileService.upsertPayoutAccount(user.getId(), "New Name", "new@upi");

        assertThat(response.accountHolderName()).isEqualTo("New Name");
        assertThat(response.upiVpa()).isEqualTo("new@upi");
        assertThat(existing.getAccountHolderName()).isEqualTo("New Name");
        assertThat(existing.getUpiVpa()).isEqualTo("new@upi");
        verify(payoutAccountRepository).save(existing);
    }

    // --- deleteAccount ---

    @Test
    void deleteAccountAnonymizesUserWhenPasswordIsCorrect() {
        User user = passwordUser();
        UUID id = user.getId();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("current-pw", "hashed-pw")).thenReturn(true);

        profileService.deleteAccount(id, "current-pw");

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getPasswordHash()).isNull();
        assertThat(user.getGoogleSub()).isNull();
        assertThat(user.getDisplayName()).isEqualTo("Deleted user");
        assertThat(user.getEmail()).isEqualTo("deleted-" + id + "@deleted.invalid");
        assertThat(user.isEmailVerified()).isFalse();
        verify(userRepository).save(user);
    }

    @Test
    void deleteAccountRejectsAWrongPasswordAndLeavesTheAccountIntact() {
        User user = passwordUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed-pw")).thenReturn(false);

        assertThatThrownBy(() -> profileService.deleteAccount(user.getId(), "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(user.getDeletedAt()).isNull();
        assertThat(user.getEmail()).isEqualTo("jane@example.com");
        verify(userRepository, never()).save(any());
    }

    @Test
    void deleteAccountAllowsGoogleOnlyAccountWithNoPassword() {
        User user = googleUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        profileService.deleteAccount(user.getId(), null);

        assertThat(user.getDeletedAt()).isNotNull();
        assertThat(user.getGoogleSub()).isNull();
        verify(userRepository).save(user);
    }
}
