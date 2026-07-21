package com.knowyourinterview.api.experience.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RoundRequest(
        @NotBlank String roundType,
        Short durationMinutes,
        String questionsAsked,
        List<String> topicsTags,
        String approach,
        String interviewerBehavior,
        @Min(1) @Max(5) Short difficulty) {
}
