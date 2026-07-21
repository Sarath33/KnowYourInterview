package com.knowyourinterview.api.payout;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.experience.Payout;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PayoutController.class)
@Import({SecurityConfig.class, JwtService.class})
class PayoutControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private PayoutService payoutService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private String bearerTokenFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "contributor@example.com", false).token();
    }

    @Test
    void mineRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/payouts/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void mineReturnsTheCallersOwnPayouts() throws Exception {
        UUID contributorId = UUID.randomUUID();
        PayoutResponse response = new PayoutResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", contributorId, null, null,
                50000, Payout.Status.PENDING, null, null, Instant.now());
        when(payoutService.listMine(contributorId)).thenReturn(List.of(response));

        mockMvc.perform(get("/api/v1/payouts/mine").header("Authorization", bearerTokenFor(contributorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company").value("Acme"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }
}
