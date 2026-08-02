package com.knowyourinterview.api.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.knowyourinterview.api.common.SecureTokens;
import com.knowyourinterview.api.email.EmailSender;
import com.knowyourinterview.api.user.EmailVerificationToken;
import com.knowyourinterview.api.user.EmailVerificationTokenRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the confirmation-code lifecycle. Repositories and the mail sender are mocked;
 * the hashing is real (SecureTokens), so these exercise the same code-in, hash-compare path
 * production does rather than a stand-in for it.
 *
 * <p>The attempt-limit cases carry the most weight. Six digits is a million possibilities, so
 * the cap is what stands between the feature and a trivially brute-forceable account
 * confirmation — a regression there wouldn't show up as a failing happy path.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "jane@example.com";
    private static final String CODE = "481902";
    private static final long CODE_TTL_MINUTES = 10;

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private EmailSender emailSender;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(userRepository, tokenRepository, emailSender, CODE_TTL_MINUTES);
    }

    private User unverifiedUser() {
        return new User(UUID.randomUUID(), EMAIL, "hashed-pw", "Jane");
    }

    private EmailVerificationToken tokenFor(User user, String code, Instant expiresAt) {
        return new EmailVerificationToken(
                UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(code), expiresAt);
    }

    private void outstanding(User user, EmailVerificationToken token) {
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.of(token));
    }

    // --- issueAndSend -----------------------------------------------------------------------

    @Test
    void issueAndSendStoresACodeAndEmailsIt() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(saved.getValue().isExpired()).isFalse();
        assertThat(saved.getValue().isUsed()).isFalse();
        assertThat(saved.getValue().getAttempts()).isZero();

        verify(emailSender).send(eq(EMAIL), anyString(), anyString(), anyString());
    }

    @Test
    void theEmailedCodeIsSixDigits() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        ArgumentCaptor<String> textBody = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(any(), any(), any(), textBody.capture());
        assertThat(textBody.getValue()).containsPattern("\\b\\d{6}\\b");
    }

    /** Otherwise a user who pressed resend five times would have five live codes — and, worse
     * for a short code, five separate guess budgets to work through. */
    @Test
    void issueAndSendInvalidatesAnyEarlierOutstandingCode() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        verify(tokenRepository).invalidateOutstandingTokens(eq(user.getId()), any());
    }

    @Test
    void issueAndSendDoesNothingForAnAlreadyVerifiedAccount() {
        User verified = User.forGoogleSignup(UUID.randomUUID(), EMAIL, "Jane", "google-sub");

        service.issueAndSend(verified);

        verify(tokenRepository, never()).save(any());
        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    // --- verify: happy path -----------------------------------------------------------------

    @Test
    void verifyMarksTheUserConfirmedAndBurnsTheCode() {
        User user = unverifiedUser();
        EmailVerificationToken token = tokenFor(user, CODE, Instant.now().plusSeconds(600));
        outstanding(user, token);

        service.verify(EMAIL, CODE);

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
    }

    /** Codes get copied out of emails with stray whitespace attached. */
    @Test
    void verifyToleratesSurroundingWhitespace() {
        User user = unverifiedUser();
        outstanding(user, tokenFor(user, CODE, Instant.now().plusSeconds(600)));

        assertThatCode(() -> service.verify(EMAIL, "  " + CODE + " ")).doesNotThrowAnyException();
        assertThat(user.isEmailVerified()).isTrue();
    }

    // --- verify: the attempt limit ----------------------------------------------------------

    @Test
    void aWrongCodeCountsAgainstTheAttemptBudget() {
        User user = unverifiedUser();
        EmailVerificationToken token = tokenFor(user, CODE, Instant.now().plusSeconds(600));
        outstanding(user, token);

        assertThatThrownBy(() -> service.verify(EMAIL, "000000")).isInstanceOf(InvalidTokenException.class);

        assertThat(token.getAttempts()).isEqualTo((short) 1);
        assertThat(token.isUsed()).as("still usable — one wrong guess isn't fatal").isFalse();
        assertThat(user.isEmailVerified()).isFalse();
    }

    /** The whole reason a 6-digit code is acceptable: the guess budget runs out long before a
     * million candidates do. */
    @Test
    void theCodeIsBurnedOnceTheAttemptBudgetIsSpent() {
        User user = unverifiedUser();
        EmailVerificationToken token = tokenFor(user, CODE, Instant.now().plusSeconds(600));
        outstanding(user, token);

        for (int i = 0; i < EmailVerificationToken.MAX_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.verify(EMAIL, "000000")).isInstanceOf(InvalidTokenException.class);
        }

        assertThat(token.getAttempts()).isEqualTo(EmailVerificationToken.MAX_ATTEMPTS);
        assertThat(token.isUsed()).as("burned, so a resend is the only way forward").isTrue();
    }

    /** Even the correct code must not work once the budget is spent — otherwise the cap is
     * merely a speed bump, and an attacker who guesses right on the last attempt still wins. */
    @Test
    void theRightCodeIsRejectedAfterTheBudgetIsSpent() {
        User user = unverifiedUser();
        EmailVerificationToken token = tokenFor(user, CODE, Instant.now().plusSeconds(600));
        for (int i = 0; i < EmailVerificationToken.MAX_ATTEMPTS; i++) {
            token.recordFailedAttempt();
        }
        outstanding(user, token);

        assertThatThrownBy(() -> service.verify(EMAIL, CODE)).isInstanceOf(InvalidTokenException.class);

        assertThat(user.isEmailVerified()).isFalse();
    }

    // --- verify: other rejections -----------------------------------------------------------

    @Test
    void verifyRejectsAnExpiredCode() {
        User user = unverifiedUser();
        outstanding(user, tokenFor(user, CODE, Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> service.verify(EMAIL, CODE)).isInstanceOf(InvalidTokenException.class);

        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void verifyRejectsWhenThereIsNoCodeOutstanding() {
        User user = unverifiedUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify(EMAIL, CODE)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void verifyRejectsAnUnknownAddress() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("ghost@example.com", CODE))
                .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * Every rejection reads the same. Distinguishing them would tell a caller which addresses
     * are registered and which have a code in flight, and the user's next step — request a new
     * code — is identical in every case.
     */
    @Test
    void everyRejectionGivesTheSameMessage() {
        User user = unverifiedUser();

        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());
        String unknownAddress = messageFrom(() -> service.verify("ghost@example.com", CODE));

        outstanding(user, tokenFor(user, CODE, Instant.now().plusSeconds(600)));
        String wrongCode = messageFrom(() -> service.verify(EMAIL, "000000"));

        assertThat(wrongCode).isEqualTo(unknownAddress);
    }

    /** Someone who confirmed on another device and retypes the code has done nothing wrong. */
    @Test
    void verifySucceedsQuietlyWhenTheAccountIsAlreadyConfirmed() {
        User verified = User.forGoogleSignup(UUID.randomUUID(), EMAIL, "Jane", "google-sub");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verified));

        assertThatCode(() -> service.verify(EMAIL, "000000")).doesNotThrowAnyException();

        // No code lookup needed at all — there's nothing left to check.
        verify(tokenRepository, never()).findFirstByUserIdAndUsedAtIsNullOrderByCreatedAtDesc(any());
    }

    // --- resend -----------------------------------------------------------------------------

    @Test
    void resendIssuesAFreshCodeForAnUnverifiedAccount() {
        User user = unverifiedUser();
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(user));

        service.resend(EMAIL);

        verify(tokenRepository).save(any());
        verify(emailSender).send(eq(EMAIL), anyString(), anyString(), anyString());
    }

    @Test
    void resendDoesNothingForAnUnknownAddress() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        service.resend("ghost@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void resendDoesNothingForAnAlreadyVerifiedAddress() {
        User verified = User.forGoogleSignup(UUID.randomUUID(), EMAIL, "Jane", "google-sub");
        when(userRepository.findByEmailIgnoreCase(EMAIL)).thenReturn(Optional.of(verified));

        service.resend(EMAIL);

        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    private String messageFrom(Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to be rejected");
        } catch (InvalidTokenException e) {
            return e.getMessage();
        }
    }
}
