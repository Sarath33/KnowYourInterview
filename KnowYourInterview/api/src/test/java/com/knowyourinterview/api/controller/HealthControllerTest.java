package com.knowyourinterview.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.knowyourinterview.api.security.JwtService;
import com.knowyourinterview.api.security.SecurityConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test: only the web layer + our real SecurityConfig (so the /api/v1/health
 * permitAll rule is actually exercised), no real DataSource/Redis needed — SecurityConfig
 * now also needs a StringRedisTemplate bean for RateLimitingFilter, hence the mock below,
 * but nothing here talks to it. Full @SpringBootTest would try to construct AuthService,
 * which needs a real DataSource too — see AuthControllerTest for how the auth endpoints
 * are tested instead (mocked service layer), or AuthFlowIT for the real-infra version.
 */
@WebMvcTest(HealthController.class)
@Import({SecurityConfig.class, JwtService.class})
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("know-your-interview-api"));
    }
}
