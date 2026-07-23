package com.knowyourinterview.api.experience.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceOutcome;

public record ExperienceTeaserResponse(
        UUID id,
        String company,
        String roleTitle,
        String level,
        String location,
        boolean isRemote,
        Short interviewMonth,
        Short interviewYear,
        ExperienceOutcome outcome,
        String teaser,
        int pricePaise,
        // Round count is safe to show pre-purchase — it signals content depth without
        // leaking any actual round content (type, questions, etc).
        int roundCount,
        Instant publishedAt,
        // False for a guest or for a signed-in viewer who hasn't purchased this one yet.
        boolean unlocked) {

    public static ExperienceTeaserResponse from(Experience e, long roundCount, boolean unlocked) {
        return new ExperienceTeaserResponse(
                e.getId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(), e.isRemote(),
                e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(), e.getPricePaise(),
                (int) roundCount, e.getPublishedAt(), unlocked);
    }
}
