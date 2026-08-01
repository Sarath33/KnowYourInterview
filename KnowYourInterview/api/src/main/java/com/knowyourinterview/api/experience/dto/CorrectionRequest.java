package com.knowyourinterview.api.experience.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for POST /admin/experiences/{id}/request-correction — parallels RejectRequest's
 * shape, but for the softer "please fix and resubmit" verdict (see
 * ExperienceStatus#CORRECTION_REQUESTED) instead of an outright rejection. */
public record CorrectionRequest(@NotBlank String notes) {
}
