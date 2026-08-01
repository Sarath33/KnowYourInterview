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
 * confidentialNote is different — it's editable on both create and edit like the other
 * content fields, just visible only to the submitter and admins (see Experience's Javadoc
 * on confidentialNote and ExperienceResponseAssembler for where it gets redacted).
 */
public record ExperienceRequest(
        @NotBlank String company,
        @NotBlank String roleTitle,
        String level,
        String location,
        Boolean isRemote,
        @Min(1) @Max(12) Short interviewMonth,
        @Min(2000) @Max(2100) Short interviewYear,
        @NotNull ExperienceOutcome outcome,
        @NotBlank String teaser,
        String prepAdvice,
        @Min(1) @Max(5) Short overallDifficulty,
        String timeline,
        String compensation,
        String sourceUrl,
        String sourceName,
        Boolean freeContribution,
        String confidentialNote) {

    // isRemote/freeContribution are boxed (unlike Experience's own primitive isRemote/
    // freeContribution fields) purely so a client that sends an explicit `null` for either
    // — a malformed payload, or a stale/corrupted browser-side autosave draft restored
    // from an older, incompatible version of a form — degrades to false instead of
    // failing Jackson deserialization outright with an opaque "Malformed request body"
    // before validation ever runs. Every caller still sees a real, non-null boolean.
    public ExperienceRequest {
        if (isRemote == null) {
            isRemote = false;
        }
        if (freeContribution == null) {
            freeContribution = false;
        }
    }
}
