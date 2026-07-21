package com.knowyourinterview.api.experience.dto;

import java.util.List;
import java.util.UUID;

import com.knowyourinterview.api.experience.ExperienceRound;

public record ExperienceRoundResponse(
        UUID id,
        short roundNumber,
        String roundType,
        Short durationMinutes,
        String questionsAsked,
        List<String> topicsTags,
        String approach,
        String interviewerBehavior,
        Short difficulty) {

    public static ExperienceRoundResponse from(ExperienceRound r) {
        List<String> tags = r.getTopicsTags() == null || r.getTopicsTags().isBlank()
                ? List.of()
                : List.of(r.getTopicsTags().split("\\s*,\\s*"));
        return new ExperienceRoundResponse(
                r.getId(), r.getRoundNumber(), r.getRoundType(), r.getDurationMinutes(),
                r.getQuestionsAsked(), tags, r.getApproach(), r.getInterviewerBehavior(), r.getDifficulty());
    }
}
