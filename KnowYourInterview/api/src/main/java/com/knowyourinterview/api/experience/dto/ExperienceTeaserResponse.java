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
        long pricePaise,
        // Round count is safe to show pre-purchase — it signals content depth without
        // leaking any actual round content (type, questions, etc).
        int roundCount,
        Instant publishedAt,
        // False for a guest or for a signed-in viewer who hasn't purchased this one yet.
        // Always effectively true for a free experience too — see ExperienceService
        // #getPublicView, which grants full access to anyone once isFree && PUBLISHED.
        boolean unlocked,
        // Admin-authored "reference a public source" submissions are always free — see
        // Experience's Javadoc on sourceUrl. pricePaise is 0 (not meaningful) when this
        // is true.
        boolean isFree,
        String sourceUrl,
        String sourceName,
        // How many distinct signed-in viewers have opened this experience's detail page
        // while PUBLISHED — one per person, not per page load, and guests aren't counted.
        // Public, shown on both the Browse card and the detail page. See
        // ExperienceRepository#incrementViewCount.
        long viewCount) {

    public static ExperienceTeaserResponse from(Experience e, long roundCount, boolean unlocked) {
        return new ExperienceTeaserResponse(
                e.getId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(), e.isRemote(),
                e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(), e.getPricePaise(),
                (int) roundCount, e.getPublishedAt(), unlocked, e.isFree(), e.getSourceUrl(), e.getSourceName(),
                e.getViewCount());
    }
}
