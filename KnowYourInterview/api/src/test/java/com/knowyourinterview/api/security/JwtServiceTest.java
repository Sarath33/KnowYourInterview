package com.knowyourinterview.api.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit test — no Spring context, no DB/Redis. */
class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("test-secret-0123456789abcdef0123456789abcdef", 15, 30);

    @Test
    void issuesAndParsesAccessToken() {
        UUID userId = UUID.randomUUID();
        JwtService.AccessToken token = jwtService.issueAccessToken(userId, "jane@example.com", true);

        JwtService.AccessClaims claims = jwtService.parseAccessToken(token.token());

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.email()).isEqualTo("jane@example.com");
        assertThat(claims.admin()).isTrue();
    }

    @Test
    void issuesAndParsesRefreshToken() {
        UUID userId = UUID.randomUUID();
        JwtService.RefreshToken token = jwtService.issueRefreshToken(userId);

        JwtService.RefreshClaims claims = jwtService.parseRefreshToken(token.token());

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.jti()).isEqualTo(token.jti());
    }

    @Test
    void rejectsRefreshTokenPresentedAsAccessToken() {
        JwtService.RefreshToken refresh = jwtService.issueRefreshToken(UUID.randomUUID());

        assertThatThrownBy(() -> jwtService.parseAccessToken(refresh.token()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTamperedToken() {
        JwtService.AccessToken token = jwtService.issueAccessToken(UUID.randomUUID(), "a@b.com", false);
        String tampered = token.token().substring(0, token.token().length() - 2) + "xx";

        assertThatThrownBy(() -> jwtService.parseAccessToken(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
