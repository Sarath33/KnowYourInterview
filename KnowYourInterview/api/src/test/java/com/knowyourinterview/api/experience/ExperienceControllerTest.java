package com.knowyourinterview.api.experience;

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

import com.knowyourinterview.api.common.PagedResponse;
import com.knowyourinterview.api.experience.dto.ExperienceEditSnapshotResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceTeaserResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
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

@WebMvcTest(ExperienceController.class)
@Import({SecurityConfig.class, JwtService.class})
class ExperienceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ExperienceService experienceService;

    // SecurityConfig now wires RateLimitingFilter, which needs this bean to exist.
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    private String bearerTokenFor(UUID userId) {
        return "Bearer " + jwtService.issueAccessToken(userId, "contributor@example.com", false).token();
    }

    private ExperienceFullResponse sampleFullResponse(UUID contributorId) {
        return new ExperienceFullResponse(
                UUID.randomUUID(), contributorId, "Acme", "Backend Engineer", "L4", "Remote", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Solid loop, focus on systems design.",
                9900, 0, null, ExperienceStatus.DRAFT, null, null, null, null, null, 0, List.of(), List.of(), true,
                false, null, null);
    }

    @Test
    void createDraftRequiresAuth() throws Exception {
        mockMvc.perform(post("/api/v1/experiences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"Acme","roleTitle":"Backend Engineer","isRemote":true,
                                 "outcome":"OFFER","teaser":"Solid loop."}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mineRequiresAuthDespiteMatchingTheSingleSegmentBrowsePattern() throws Exception {
        // Regression check: "/mine" is one path segment, same shape as "/api/v1/experiences/{id}",
        // so it must be explicitly excluded from the public browse permitAll rule in SecurityConfig.
        mockMvc.perform(get("/api/v1/experiences/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void createDraftReturns201WhenAuthenticated() throws Exception {
        UUID contributorId = UUID.randomUUID();
        when(experienceService.createDraft(eq(contributorId), eq(false), any()))
                .thenReturn(sampleFullResponse(contributorId));

        mockMvc.perform(post("/api/v1/experiences")
                        .header("Authorization", bearerTokenFor(contributorId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"company":"Acme","roleTitle":"Backend Engineer","isRemote":true,
                                 "outcome":"OFFER","teaser":"Solid loop."}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.company").value("Acme"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void browseIsPublic() throws Exception {
        ExperienceTeaserResponse teaser = new ExperienceTeaserResponse(
                UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Remote", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Solid loop.", 9900, 4, Instant.now(), false,
                false, null, null);
        when(experienceService.browsePublished(
                        any(), any(), any(), any(), any(), any(), any(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new PagedResponse<>(List.of(teaser), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/experiences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].company").value("Acme"));
    }

    @Test
    void getOneIsPublicAndReturnsTeaserByDefault() throws Exception {
        UUID id = UUID.randomUUID();
        ExperienceTeaserResponse teaser = new ExperienceTeaserResponse(
                id, "Acme", "Backend Engineer", "L4", "Remote", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Solid loop.", 9900, 4, Instant.now(), false,
                false, null, null);
        when(experienceService.getPublicView(isNull(), eq(false), eq(id)))
                .thenReturn(ExperienceViewResponse.teaserOnly(teaser));

        mockMvc.perform(get("/api/v1/experiences/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled").value(false))
                .andExpect(jsonPath("$.teaser.company").value("Acme"))
                .andExpect(jsonPath("$.full").doesNotExist());
    }

    @Test
    void historyRequiresAuth() throws Exception {
        // Regression check, same shape as mineRequiresAuthDespiteMatchingTheSingleSegmentBrowsePattern
        // above: "/{id}/history" has two segments after "/experiences/", so it doesn't
        // match the single-segment "/api/v1/experiences/*" permitAll rule in
        // SecurityConfig — but worth a direct check since a future refactor of that
        // pattern could silently widen it.
        mockMvc.perform(get("/api/v1/experiences/" + UUID.randomUUID() + "/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void historyReturnsSnapshotsWhenAuthenticated() throws Exception {
        UUID contributorId = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        ExperienceEditSnapshotResponse snapshot = new ExperienceEditSnapshotResponse(
                UUID.randomUUID(), Instant.now(), "Old Co", "Backend Engineer", "L4", "Remote", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "old teaser", "old advice", (short) 3,
                "3 weeks", "35 LPA", List.of("Company"));
        when(experienceService.listEditHistory(eq(contributorId), eq(false), eq(id)))
                .thenReturn(List.of(snapshot));

        mockMvc.perform(get("/api/v1/experiences/" + id + "/history")
                        .header("Authorization", bearerTokenFor(contributorId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company").value("Old Co"))
                .andExpect(jsonPath("$[0].changedFields[0]").value("Company"));
    }
}
