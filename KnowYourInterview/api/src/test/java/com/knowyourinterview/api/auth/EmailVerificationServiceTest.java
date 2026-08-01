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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the confirm-your-email lifecycle. Repositories and the mail sender are
 * mocked; the token hashing is real (SecureTokens), so the tests exercise the same
 * raw-token-in, hash-out path production does rather than a stand-in for it.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String WEB_BASE_URL = "http://localhost:5173";
    private static final long TOKEN_TTL_HOURS = 24;

    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationTokenRepository tokenRepository;
    @Mock
    private EmailSender emailSender;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(
                userRepository, tokenRepository, emailSender, TOKEN_TTL_HOURS, WEB_BASE_URL);
    }

    private User unverifiedUser() {
        return new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
    }

    // --- issueAndSend ---

    @Test
    void issueAndSendStoresATokenAndEmailsALinkToTheConfirmRoute() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        ArgumentCaptor<EmailVerificationToken> token = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(token.capture());
        assertThat(token.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(token.getValue().isExpired()).isFalse();
        assertThat(token.getValue().isUsed()).isFalse();

        ArgumentCaptor<String> textBody = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("jane@example.com"), anyString(), anyString(), textBody.capture());
        assertThat(textBody.getValue()).contains(WEB_BASE_URL + "/confirm-email?token=");
    }

    /** The raw token only ever exists in the link. Storing it would mean a database leak
     * hands out working confirmations. */
    @Test
    void issueAndSendStoresOnlyAHashNotTheRawToken() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        ArgumentCaptor<EmailVerificationToken> token = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(token.capture());
        ArgumentCaptor<String> textBody = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(any(), any(), any(), textBody.capture());

        String rawToken = textBody.getValue().split("\\?token=")[1].split("\\s")[0];
        assertThat(token.getValue().getTokenHash()).isNotEqualTo(rawToken);
        assertThat(token.getValue().getTokenHash()).isEqualTo(SecureTokens.sha256Hex(rawToken));
    }

    /** Otherwise a user who pressed "resend" five times would have five live confirmation
     * links sitting in their inbox, each one a working credential. */
    @Test
    void issueAndSendInvalidatesAnyEarlierOutstandingToken() {
        User user = unverifiedUser();

        service.issueAndSend(user);

        verify(tokenRepository).invalidateOutstandingTokens(eq(user.getId()), any());
    }

    /** Nothing to confirm, and issuing a live token for a confirmed address would be handing
     * out a credential nobody asked for. */
    @Test
    void issueAndSendDoesNothingForAnAlreadyVerifiedAccount() {
        User user = User.forGoogleSignup(UUID.randomUUID(), "jane@example.com", "Jane", "google-sub");

        service.issueAndSend(user);

        verify(tokenRepository, never()).save(any());
        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    // --- verify ---

    @Test
    void verifyMarksTheUserConfirmedAndBurnsTheToken() {
        User user = unverifiedUser();
        String rawToken = "raw-token-value";
        EmailVerificationToken token = new EmailVerificationToken(
                UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(3600));
        when(tokenRepository.findByTokenHash(SecureTokens.sha256Hex(rawToken))).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.verify(rawToken);

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
    }

    @Test
    void verifyRejectsAnUnknownToken() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("nonsense"))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("isn't valid");
    }

    @Test
    void verifyRejectsAnExpiredToken() {
        User user = unverifiedUser();
        String rawToken = "raw-token-value";
        EmailVerificationToken token = new EmailVerificationToken(
                UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(rawToken),
                Instant.now().minusSeconds(60));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verify(rawToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired");

        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void verifyRejectsAReusedToken() {
        User user = unverifiedUser();
        String rawToken = "raw-token-value";
        EmailVerificationToken token = new EmailVerificationToken(
                UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(3600));
        token.markUsed();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.verify(rawToken))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("already been used");
    }

    /** Mail clients prefetch links, and people click twice. Someone who is already confirmed
     * should see success, not an error about a token they no longer need — so the
     * already-verified check deliberately comes before the used/expired ones. */
    @Test
    void verifySucceedsQuietlyWhenTheAccountIsAlreadyConfirmed() {
        User user = User.forGoogleSignup(UUID.randomUUID(), "jane@example.com", "Jane", "google-sub");
        String rawToken = "raw-token-value";
        EmailVerificationToken token = new EmailVerificationToken(
                UUID.randomUUID(), user.getId(), SecureTokens.sha256Hex(rawToken),
                Instant.now().plusSeconds(3600));
        token.markUsed();
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        service.verify(rawToken);

        assertThat(user.isEmailVerified()).isTrue();
    }

    // --- resend ---

    @Test
    void resendIssuesAFreshLinkForAnUnverifiedAccount() {
        User user = unverifiedUser();
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));

        service.resend("jane@example.com");

        verify(tokenRepository).save(any());
        verify(emailSender).send(eq("jane@example.com"), anyString(), anyString(), anyString());
    }

    /** No-enumeration posture, same as forgot-password: an unknown address produces exactly
     * the same (empty) behaviour as a known one, and the controller's response is identical
     * either way. */
    @Test
    void resendDoesNothingForAnUnknownAddress() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        service.resend("ghost@example.com");

        verify(tokenRepository, never()).save(any());
        verify(emailSender, never()).send(any(), any(), any(), any());
    }

    @Test
    void resendDoesNothingForAnAlreadyVerifiedAddress() {
        User verified = User.forGoogleSignup(UUID.randomUUID(), "jane@example.com", "Jane", "google-sub");
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(verified));

        service.resend("jane@example.com");

        verify(emailSender, never()).send(any(), any(), any(), any());
    }
}
