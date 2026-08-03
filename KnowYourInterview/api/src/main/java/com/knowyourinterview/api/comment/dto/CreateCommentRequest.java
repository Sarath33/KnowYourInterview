package com.knowyourinterview.api.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/v1/experiences/{experienceId}/comments}.
 * <p>
 * {@code parentId} is an optional UUID-as-string: null/absent for a top-level comment, or the
 * id of an existing, non-deleted top-level comment of the same experience for a reply. It's
 * carried as a String (not a typed UUID) so a malformed value surfaces as a clean 400 from
 * the service rather than a bind failure; the service parses and validates it.
 * <p>
 * {@code @NotBlank}/{@code @Size} give the standard field-error envelope on an empty or
 * over-long body; the service repeats an empty/length guard defensively.
 */
public record CreateCommentRequest(
        @NotBlank @Size(max = 4000) String body,
        String parentId) {
}
