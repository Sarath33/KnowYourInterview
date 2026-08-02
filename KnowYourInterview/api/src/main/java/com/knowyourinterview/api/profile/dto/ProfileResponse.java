package com.knowyourinterview.api.profile.dto;

import com.knowyourinterview.api.auth.dto.UserResponse;

/**
 * Everything the account page renders in one payload: the core user, which credentials are on
 * file (to decide whether "change password" needs the current one and whether email is
 * editable), the payout destination (null until set), and the earnings/activity counters.
 * <p>
 * Field names are the API contract the web client is built against — do not rename.
 */
public record ProfileResponse(
        UserResponse user,
        boolean hasPassword,
        boolean hasGoogle,
        PayoutAccountResponse payoutAccount,
        long totalEarnedPaise,
        long pendingPayoutPaise,
        long submissionCount,
        long purchaseCount) {
}
