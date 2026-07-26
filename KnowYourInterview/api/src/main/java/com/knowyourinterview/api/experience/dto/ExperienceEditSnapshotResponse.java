package com.knowyourinterview.api.experience.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.knowyourinterview.api.experience.ExperienceEditSnapshot;
import com.knowyourinterview.api.experience.ExperienceOutcome;

/** One prior version of an experience's top-level fields, plus which of those fields the
 * edit right after this snapshot actually changed — diffed server-side against whatever
 * state came next (either a newer snapshot, or the current live experience for the most
 * recent one), so the UI doesn't have to duplicate the comparison logic. */
public record ExperienceEditSnapshotResponse(
        UUID id,
        Instant recordedAt,
        String company,
        String roleTitle,
        String level,
        String location,
        boolean isRemote,
        Short interviewMonth,
        Short interviewYear,
        ExperienceOutcome outcome,
        String teaser,
        String prepAdvice,
        Short overallDifficulty,
        String timeline,
        String compensation,
        List<String> changedFields) {

    public static ExperienceEditSnapshotResponse from(ExperienceEditSnapshot s, List<String> changedFields) {
        return new ExperienceEditSnapshotResponse(
                s.getId(), s.getRecordedAt(), s.getCompany(), s.getRoleTitle(), s.getLevel(), s.getLocation(),
                s.isRemote(), s.getInterviewMonth(), s.getInterviewYear(), s.getOutcome(), s.getTeaser(),
                s.getPrepAdvice(), s.getOverallDifficulty(), s.getTimeline(), s.getCompensation(), changedFields);
    }
}
