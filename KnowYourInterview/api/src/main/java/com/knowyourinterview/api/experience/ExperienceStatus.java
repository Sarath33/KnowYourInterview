package com.knowyourinterview.api.experience;

public enum ExperienceStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    REJECTED,
    // An admin reviewed the submission, edited it directly and/or left notes on what
    // still needs fixing, and wants the contributor to revise and resubmit — distinct
    // from REJECTED, which is a harder "no" with no expectation of a quick fix. See
    // Experience#requestCorrection and AdminReviewService#requestCorrection.
    CORRECTION_REQUESTED,
    PUBLISHED
}
