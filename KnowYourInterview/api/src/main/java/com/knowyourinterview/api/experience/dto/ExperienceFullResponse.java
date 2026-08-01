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
        long pricePaise,
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
        // Set alongside status == CORRECTION_REQUESTED — see Experience#requestCorrection.
        // Same shape/lifecycle as rejectionReason above, just for the softer "please fix
        // and resubmit" verdict instead of an outright rejection.
        String correctionNotes,
        // How many people hold a real (paid) Entitlement for this experience — visible to
        // whoever gets full access (owner, admin, or a purchaser), same audience as
        // everything else on this DTO. Not shown on the public teaser.
        long unlockCount,
        List<ExperienceRoundResponse> rounds,
        List<ProofDocumentResponse> proofDocuments,
        // Mirrors ExperienceTeaserResponse#unlocked for the same shared/types.ts extends
        // reason as roundCount above. Always true here: reaching a full response at all
        // means the caller is the owner, an admin, a paying entitlement holder, or (for a
        // free experience) any viewer at all — every one of those already has full access,
        // so there's no "locked" full response.
        boolean unlocked,
        // Mirrors ExperienceTeaserResponse#isFree/sourceUrl/sourceName — see Experience's
        // Javadoc on those fields.
        boolean isFree,
        String sourceUrl,
        String sourceName,
        // Mirrors ExperienceTeaserResponse#viewCount — see Experience's Javadoc on the field.
        long viewCount,
        // Submitter-authored, admin-only-visible — see Experience's Javadoc on
        // confidentialNote. Null here whenever the caller isn't the owner or an admin;
        // see ExperienceResponseAssembler#toFullResponse's includeConfidentialNote param.
        String confidentialNote) {

    /** Owner/admin-facing overload — always includes confidentialNote. */
    public static ExperienceFullResponse from(
            Experience e, List<ExperienceRoundResponse> rounds, List<ProofDocumentResponse> proof, long unlockCount) {
        return from(e, rounds, proof, unlockCount, true);
    }

    public static ExperienceFullResponse from(
            Experience e, List<ExperienceRoundResponse> rounds, List<ProofDocumentResponse> proof, long unlockCount,
            boolean includeConfidentialNote) {
        return new ExperienceFullResponse(
                e.getId(), e.getContributorId(), e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(),
                e.isRemote(), e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(),
                e.getPricePaise(), rounds.size(), e.getPublishedAt(), e.getStatus(), e.getPrepAdvice(),
                e.getOverallDifficulty(), e.getTimeline(), e.getCompensation(), e.getRejectionReason(),
                e.getCorrectionNotes(), unlockCount, rounds, proof, true, e.isFree(), e.getSourceUrl(),
                e.getSourceName(), e.getViewCount(), includeConfidentialNote ? e.getConfidentialNote() : null);
    }
}
