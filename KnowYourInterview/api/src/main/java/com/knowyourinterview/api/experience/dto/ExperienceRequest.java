package com.knowyourinterview.api.experience.dto;

import com.knowyourinterview.api.experience.ExperienceOutcome;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Used for both create (draft) and edit — price isn't here; the platform sets it. */
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
        String compensation) {
}
