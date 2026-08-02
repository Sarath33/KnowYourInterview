package com.knowyourinterview.api.profile.dto;

import com.knowyourinterview.api.user.PayoutAccount;

/** The payout destination as the profile page shows it — just who's paid and their UPI VPA. */
public record PayoutAccountResponse(String accountHolderName, String upiVpa) {

    public static PayoutAccountResponse from(PayoutAccount account) {
        return new PayoutAccountResponse(account.getAccountHolderName(), account.getUpiVpa());
    }
}
