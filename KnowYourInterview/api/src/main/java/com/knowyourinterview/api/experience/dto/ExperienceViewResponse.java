package com.knowyourinterview.api.experience.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Matches shared/types.ts `ExperienceView` discriminated union exactly:
 * {"entitled": false, "teaser": {...}} or {"entitled": true, "full": {...}} —
 * never both keys present, hence NON_NULL inclusion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExperienceViewResponse(boolean entitled, ExperienceTeaserResponse teaser, ExperienceFullResponse full) {

    public static ExperienceViewResponse teaserOnly(ExperienceTeaserResponse teaser) {
        return new ExperienceViewResponse(false, teaser, null);
    }

    public static ExperienceViewResponse fullAccess(ExperienceFullResponse full) {
        return new ExperienceViewResponse(true, null, full);
    }
}
