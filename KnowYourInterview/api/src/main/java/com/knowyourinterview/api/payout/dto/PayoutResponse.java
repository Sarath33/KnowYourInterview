package com.knowyourinterview.api.payout.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.Payout;
import com.knowyourinterview.api.user.User;

public record PayoutResponse(
        UUID id,
        UUID experienceId,
        String company,
        String roleTitle,
        UUID contributorId,
        String contributorEmail,
        String contributorDisplayName,
        int amountPaise,
        Payout.Status status,
        String payoutReference,
        Instant paidAt,
        Instant createdAt) {

    /** For the admin queue — includes who the contributor is. */
    public static PayoutResponse forAdmin(Payout payout, Experience experience, User contributor) {
        return new PayoutResponse(
                payout.getId(), payout.getExperienceId(), experience.getCompany(), experience.getRoleTitle(),
                contributor.getId(), contributor.getEmail(), contributor.getDisplayName(), payout.getAmountPaise(),
                payout.getStatus(), payout.getPayoutReference(),
                payout.getStatus() == Payout.Status.PAID ? payout.getUpdatedAt() : null, payout.getCreatedAt());
    }

    /** For a contributor's own "my payouts" view — no need to repeat their own identity. */
    public static PayoutResponse forContributor(Payout payout, Experience experience) {
        return new PayoutResponse(
                payout.getId(), payout.getExperienceId(), experience.getCompany(), experience.getRoleTitle(),
                payout.getContributorId(), null, null, payout.getAmountPaise(), payout.getStatus(),
                payout.getPayoutReference(),
                payout.getStatus() == Payout.Status.PAID ? payout.getUpdatedAt() : null, payout.getCreatedAt());
    }
}
