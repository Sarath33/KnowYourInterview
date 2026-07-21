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
        Instant publishedAt) {

    public static ExperienceTeaserResponse from(Experience e) {
        return new ExperienceTeaserResponse(
                e.getId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(), e.isRemote(),
                e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(), e.getPricePaise(),
                e.getPublishedAt());
    }
}
