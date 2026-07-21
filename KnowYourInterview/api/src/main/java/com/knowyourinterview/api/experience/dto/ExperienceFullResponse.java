package com.knowyourinterview.api.experience.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceOutcome;
import com.knowyourinterview.api.experience.ExperienceStatus;

public record ExperienceFullResponse(
        UUID id,
        UUID contributorId,
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
        Instant publishedAt,
        ExperienceStatus status,
        String prepAdvice,
        Short overallDifficulty,
        String timeline,
        String compensation,
        String rejectionReason,
        List<ExperienceRoundResponse> rounds,
        List<ProofDocumentResponse> proofDocuments) {

    public static ExperienceFullResponse from(
            Experience e, List<ExperienceRoundResponse> rounds, List<ProofDocumentResponse> proof) {
        return new ExperienceFullResponse(
                e.getId(), e.getContributorId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(),
                e.isRemote(), e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(),
                e.getPricePaise(), e.getPublishedAt(), e.getStatus(), e.getPrepAdvice(), e.getOverallDifficulty(),
                e.getTimeline(), e.getCompensation(), e.getRejectionReason(), rounds, proof);
    }
}
