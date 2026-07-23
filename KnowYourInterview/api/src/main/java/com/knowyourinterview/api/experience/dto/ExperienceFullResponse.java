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
        // Mirrors ExperienceTeaserResponse#roundCount — shared/types.ts's ExperienceFull
        // extends ExperienceTeaser, so this needs to be present here too, not just on the
        // teaser DTO. Trivially derived from the already-loaded rounds list below.
        int roundCount,
        Instant publishedAt,
        ExperienceStatus status,
        String prepAdvice,
        Short overallDifficulty,
        String timeline,
        String compensation,
        String rejectionReason,
        // How many people hold a real (paid) Entitlement for this experience — visible to
        // whoever gets full access (owner, admin, or a purchaser), same audience as
        // everything else on this DTO. Not shown on the public teaser.
        long unlockCount,
        List<ExperienceRoundResponse> rounds,
        List<ProofDocumentResponse> proofDocuments,
        // Mirrors ExperienceTeaserResponse#unlocked for the same shared/types.ts extends
        // reason as roundCount above. Always true here: reaching a full response at all
        // means the caller is the owner, an admin, or a paying entitlement holder — every
        // one of those already has full access, so there's no "locked" full response.
        boolean unlocked) {

    public static ExperienceFullResponse from(
            Experience e, List<ExperienceRoundResponse> rounds, List<ProofDocumentResponse> proof, long unlockCount) {
        return new ExperienceFullResponse(
                e.getId(), e.getContributorId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(),
                e.isRemote(), e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(),
                e.getPricePaise(), rounds.size(), e.getPublishedAt(), e.getStatus(), e.getPrepAdvice(),
                e.getOverallDifficulty(), e.getTimeline(), e.getCompensation(), e.getRejectionReason(), unlockCount,
                rounds, proof, true);
    }
}
