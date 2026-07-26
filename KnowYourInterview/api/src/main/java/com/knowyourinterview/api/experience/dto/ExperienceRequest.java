package com.knowyourinterview.api.experience.dto;

import com.knowyourinterview.api.experience.ExperienceOutcome;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Used for both create (draft) and edit — price isn't here; the platform sets it.
 * sourceUrl/sourceName/freeContribution are create-only: ExperienceService#createDraft reads
 * them to set up either an admin-only "reference a public source" submission (see
 * Experience's Javadoc on sourceUrl/sourceName) or a contributor's own free, unreviewed
 * submission (see Experience#markAsFreeContribution), but updateDraft/applyEdits never
 * touches any of the three — sending them on an edit request is simply ignored, not an error.
 */
public record ExperienceRequest(
        @NotBlank String company,
        @NotBlank String roleTitle,
        String level,
        String location,
        boolean isRemote,
        @Min(1) @Max(12) Short interviewMonth,
        Short interviewYear,
        @NotNull ExperienceOutcome outcome,
        @NotBlank String teaser,
        String prepAdvice,
        @Min(1) @Max(5) Short overallDifficulty,
        String timeline,
        String compensation,
        String sourceUrl,
        String sourceName,
        boolean freeContribution) {
}
