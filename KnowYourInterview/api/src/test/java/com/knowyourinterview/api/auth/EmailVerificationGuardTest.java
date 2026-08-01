package com.knowyourinterview.api.auth;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationGuardTest {

    @Mock
    private UserRepository userRepository;

    private EmailVerificationGuard guard;

    @BeforeEach
    void setUp() {
        guard = new EmailVerificationGuard(userRepository);
    }

    @Test
    void allowsAConfirmedAccountThrough() {
        User verified = User.forGoogleSignup(UUID.randomUUID(), "jane@example.com", "Jane", "google-sub");
        when(userRepository.findById(verified.getId())).thenReturn(Optional.of(verified));

        assertThatCode(() -> guard.requireVerified(verified.getId(), "submitting an experience"))
                .doesNotThrowAnyException();
    }

    /** The message is shown to the user verbatim, so it has to name the action and point
     * somewhere useful rather than just saying "forbidden". */
    @Test
    void blocksAnUnconfirmedAccountWithAnActionableMessage() {
        User unverified = new User(UUID.randomUUID(), "jane@example.com", "hashed-pw", "Jane");
        when(userRepository.findById(unverified.getId())).thenReturn(Optional.of(unverified));

        assertThatThrownBy(() -> guard.requireVerified(unverified.getId(), "unlocking an experience"))
                .isInstanceOf(EmailNotVerifiedException.class)
                .hasMessageContaining("unlocking an experience")
                .hasMessageContaining("request a new link");
    }

    /** Shouldn't be reachable for a caller holding a valid token, but a guard that fails open
     * on a missing row is worse than one that fails closed on an impossible one. */
    @Test
    void failsClosedWhenTheUserRowIsMissing() {
        UUID ghostId = UUID.randomUUID();
        when(userRepository.findById(ghostId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> guard.requireVerified(ghostId, "submitting an experience"))
                .isInstanceOf(EmailNotVerifiedException.class);
    }
}
