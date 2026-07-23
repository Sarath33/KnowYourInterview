package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminReviewController.class)
@Import({SecurityConfig.class, JwtService.class})
class AdminReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AdminReviewService adminReviewService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private String tokenFor(boolean admin) {
        return "Bearer " + jwtService.issueAccessToken(UUID.randomUUID(), "user@example.com", admin).token();
    }

    private ExperienceFullResponse sample() {
        return new ExperienceFullResponse(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Remote", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Solid loop.", 9900, 0, null,
                ExperienceStatus.PENDING_REVIEW, null, null, null, null, null, 0, List.of(), List.of(), true);
    }

    @Test
    void reviewQueueRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/v1/admin/experiences")).andExpect(status().isUnauthorized());
    }

    @Test
    void reviewQueueRejectsNonAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/experiences").header("Authorization", tokenFor(false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void reviewQueueAllowsAdmin() throws Exception {
        when(adminReviewService.reviewQueue()).thenReturn(List.of(sample()));

        mockMvc.perform(get("/api/v1/admin/experiences").header("Authorization", tokenFor(true)))
                .andExpect(status().isOk());
    }

    @Test
    void approveAllowsAdmin() throws Exception {
        when(adminReviewService.approve(any(), any())).thenReturn(sample());

        mockMvc.perform(post("/api/v1/admin/experiences/" + UUID.randomUUID() + "/approve")
                        .header("Authorization", tokenFor(true)))
                .andExpect(status().isOk());
    }
}
