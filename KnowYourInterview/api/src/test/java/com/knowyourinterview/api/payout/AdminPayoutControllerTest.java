package com.knowyourinterview.api.payout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.experience.Payout;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminPayoutController.class)
@Import({SecurityConfig.class, JwtService.class})
class AdminPayoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PayoutService payoutService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private String tokenFor(boolean admin) {
        return "Bearer " + jwtService.issueAccessToken(UUID.randomUUID(), "user@example.com", admin).token();
    }

    private PayoutResponse sample(Payout.Status status) {
        return new PayoutResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", UUID.randomUUID(),
                "contributor@example.com", "Jamie Contributor", 50000, status, null,
                status == Payout.Status.PAID ? Instant.now() : null, Instant.now());
    }

    @Test
    void queueRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payouts")).andExpect(status().isUnauthorized());
    }

    @Test
    void queueRejectsNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/payouts").header("Authorization", tokenFor(false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void queueAllowsAdmin() throws Exception {
        when(payoutService.queue()).thenReturn(List.of(sample(Payout.Status.PENDING)));

        mockMvc.perform(get("/api/v1/admin/payouts").header("Authorization", tokenFor(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].contributorEmail").value("contributor@example.com"));
    }

    @Test
    void markPaidRequiresAdmin() throws Exception {
        UUID payoutId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/payouts/" + payoutId + "/mark-paid")
                        .header("Authorization", tokenFor(false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void markPaidAllowsAdminWithoutBody() throws Exception {
        UUID payoutId = UUID.randomUUID();
        when(payoutService.markPaid(any(), eq(payoutId), isNull())).thenReturn(sample(Payout.Status.PAID));

        mockMvc.perform(post("/api/v1/admin/payouts/" + payoutId + "/mark-paid")
                        .header("Authorization", tokenFor(true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void markPaidPassesThroughAnOptionalReference() throws Exception {
        UUID payoutId = UUID.randomUUID();
        when(payoutService.markPaid(any(), eq(payoutId), eq("UPI-REF-123"))).thenReturn(sample(Payout.Status.PAID));

        mockMvc.perform(post("/api/v1/admin/payouts/" + payoutId + "/mark-paid")
                        .header("Authorization", tokenFor(true))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reference":"UPI-REF-123"}
                                """))
                .andExpect(status().isOk());
    }
}
