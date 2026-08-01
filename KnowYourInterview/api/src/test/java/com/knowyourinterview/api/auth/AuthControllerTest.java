package com.knowyourinterview.api.auth;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.auth.dto.AuthResponse;
import com.knowyourinterview.api.auth.dto.UserResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    // AuthController also owns the two email-confirmation routes now.
    @MockitoBean
    private EmailVerificationService emailVerificationService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist —
    // unused directly by these tests otherwise.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void stubRateLimiterCounter() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);
    }

    private AuthResponse sampleAuthResponse() {
        UserResponse user = new UserResponse(
                UUID.randomUUID(), "jane@example.com", "Jane", false, false, Instant.now());
        return new AuthResponse("access-token", "refresh-token", user);
    }

    @Test
    void registerReturns201WithTokens() throws Exception {
        when(authService.register(anyString(), anyString(), anyString())).thenReturn(sampleAuthResponse());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","password":"supersecret","displayName":"Jane"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.user.email").value("jane@example.com"));
    }

    @Test
    void registerRejectsInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"supersecret","displayName":"Jane"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void googleReturns200WithTokens() throws Exception {
        when(authService.googleLogin(anyString())).thenReturn(sampleAuthResponse());

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"a-google-id-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"));
    }

    @Test
    void googleReturns503WhenNotConfigured() throws Exception {
        when(authService.googleLogin(anyString())).thenThrow(new GoogleAuthNotConfiguredException());

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":"a-google-id-token"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void loginReturns401OnBadCredentials() throws Exception {
        when(authService.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","password":"wrongpassword"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forgotPasswordAlwaysReturns200() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"anyone@example.com"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void bootstrapAdminReturns200OnSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","secret":"the-real-secret"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("jane@example.com is now an admin."));
    }

    @Test
    void bootstrapAdminReturns503WhenNotConfigured() throws Exception {
        doThrow(new AdminBootstrapNotConfiguredException())
                .when(authService).bootstrapAdmin(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","secret":"anything"}
                                """))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void bootstrapAdminReturns401OnWrongSecret() throws Exception {
        doThrow(new InvalidTokenException("Invalid bootstrap secret"))
                .when(authService).bootstrapAdmin(anyString(), anyString());

        mockMvc.perform(post("/api/v1/auth/bootstrap-admin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"jane@example.com","secret":"wrong"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
