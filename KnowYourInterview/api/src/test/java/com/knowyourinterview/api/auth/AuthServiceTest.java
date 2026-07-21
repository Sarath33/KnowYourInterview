package com.knowyourinterview.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.user.PasswordResetToken;
import com.knowyourinterview.api.user.PasswordResetTokenRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for AuthService's business logic — all collaborators (repositories,
 * PasswordEncoder, JwtService, Redis) are mocked, so no Spring context/DB/Redis is needed.
 * The full-stack behavior (real DB, real Redis) is covered separately by AuthFlowIT.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final long PASSWORD_RESET_TTL_MINUTES = 30;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordResetTokenRepository,
                passwordEncoder,
                jwtService,
                redisTemplate,
                PASSWORD_RESET_TTL_MINUTES);
    }

    private void stubTokenIssuance() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(jwtService.issueAccessToken(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new JwtService.AccessToken("access-token", Instant.now().plusSeconds(900)));
        when(jwtService.issueRefreshToken(any()))
                .thenReturn(new JwtService.RefreshToken("refresh-token", "jti-123", Instant.now().plusSeconds(86400)));
        when(jwtService.refreshTtl()).thenReturn(Duration.ofDays(30));
    }

    @Test
    void registerCreatesUserAndIssuesTokensWhenEmailIsFree() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(false);
        when(passwordEncoder.encode("hunter2")).thenReturn("hashed-pw");
        stubTokenIssuance();

        AuthResponse response = authService.register("jane@example.com", "hunter2", "Jane");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.user().email()).isEqualTo("jane@example.com");
        assertThat(response.user().displayName()).isEqualTo("Jane");

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(savedUser.getValue().isAdmin()).isFalse();

        verify(valueOperations).set(eq("refresh:jti-123"), eq(savedUser.getValue().getId().toString()), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmailIgnoreCase("jane@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("jane@example.com", "hunter2", "Jane"))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        User user = new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("hunter2", "hashed-pw")).thenReturn(true);
        stubTokenIssuance();

        AuthResponse response = authService.login("jane@example.com", "hunter2");

        assertThat(response.user().email()).isEqualTo("jane@example.com");
    }

    @Test
    void loginRejectsUnknownEmailWithoutRevealingWhichPartWasWrong() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@example.com", "hunter2"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void loginRejectsWrongPassword() {
        User user = new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed-pw")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("jane@example.com", "wrong"))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void refreshRotatesTokenWhenValidAndUnused() {
        UUID userId = UUID.randomUUID();
        User user = new User(userId, "jane@example.com", "hashed-pw", "Jane");
        when(jwtService.parseRefreshToken("old-refresh")).thenReturn(new JwtService.RefreshClaims(userId, "old-jti"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("refresh:old-jti")).thenReturn(userId.toString());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        stubTokenIssuance();

        AuthResponse response = authService.refresh("old-refresh");

        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(redisTemplate).delete("refresh:old-jti");
    }

    @Test
    void refreshRejectsTokenNotFoundInRedis() {
        UUID userId = UUID.randomUUID();
        when(jwtService.parseRefreshToken("stale-refresh")).thenReturn(new JwtService.RefreshClaims(userId, "stale-jti"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("refresh:stale-jti")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh("stale-refresh"))
                .isInstanceOf(InvalidTokenException.class);

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void refreshRejectsTokenThatDoesNotMatchStoredUser() {
        UUID userId = UUID.randomUUID();
        UUID differentUserId = UUID.randomUUID();
        when(jwtService.parseRefreshToken("mismatched")).thenReturn(new JwtService.RefreshClaims(userId, "jti"));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("refresh:jti")).thenReturn(differentUserId.toString());

        assertThatThrownBy(() -> authService.refresh("mismatched"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void refreshRejectsUnparseableToken() {
        when(jwtService.parseRefreshToken("garbage")).thenThrow(new RuntimeException("bad token"));

        assertThatThrownBy(() -> authService.refresh("garbage"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void logoutRevokesTokenWhenParseable() {
        when(jwtService.parseRefreshToken("valid-refresh")).thenReturn(new JwtService.RefreshClaims(UUID.randomUUID(), "jti-1"));

        authService.logout("valid-refresh");

        verify(redisTemplate).delete("refresh:jti-1");
    }

    @Test
    void logoutIsIdempotentForAlreadyInvalidToken() {
        when(jwtService.parseRefreshToken("garbage")).thenThrow(new RuntimeException("bad token"));

        authService.logout("garbage");

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void forgotPasswordDoesNothingObservableForUnknownEmail() {
        when(userRepository.findByEmailIgnoreCase("ghost@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword("ghost@example.com");

        verify(passwordResetTokenRepository, never()).save(any());
    }

    @Test
    void forgotPasswordCreatesTokenForKnownEmail() {
        User user = new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
        when(userRepository.findByEmailIgnoreCase("jane@example.com")).thenReturn(Optional.of(user));

        authService.forgotPassword("jane@example.com");

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(user.getId());
        assertThat(captor.getValue().isExpired()).isFalse();
    }

    @Test
    void resetPasswordUpdatesHashAndMarksTokenUsed() {
        User user = new User(UUID.randomUUID(), "jane@example.com", "old-hash", "Jane");
        PasswordResetToken token = new PasswordResetToken(
                UUID.randomUUID(), user.getId(), "irrelevant-hash-in-test", Instant.now().plusSeconds(600));
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newpassword")).thenReturn("new-hash");

        authService.resetPassword("raw-token", "newpassword");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(token.isUsed()).isTrue();
        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
    }

    @Test
    void resetPasswordRejectsUnknownToken() {
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword("raw-token", "newpassword"))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void resetPasswordRejectsExpiredToken() {
        PasswordResetToken expired = new PasswordResetToken(
                UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now().minusSeconds(60));
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.resetPassword("raw-token", "newpassword"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetPasswordRejectsAlreadyUsedToken() {
        PasswordResetToken used = new PasswordResetToken(
                UUID.randomUUID(), UUID.randomUUID(), "hash", Instant.now().plusSeconds(600));
        used.markUsed();
        when(passwordResetTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> authService.resetPassword("raw-token", "newpassword"))
                .isInstanceOf(InvalidTokenException.class);

        verify(userRepository, never()).save(any());
    }
}
