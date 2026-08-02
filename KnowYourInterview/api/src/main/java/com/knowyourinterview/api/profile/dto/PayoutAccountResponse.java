package com.knowyourinterview.api.profile.dto;

/**
 * The payout destination as the profile page shows it — just who's paid and their UPI VPA.
 * <p>
 * The {@code upiVpa} here is always plaintext. The value is stored encrypted at rest (see
 * {@link com.knowyourinterview.api.common.crypto.AesGcmEncryptor}), so ProfileService decrypts it
 * when building this response — the API contract stays {@code {accountHolderName, upiVpa}} in the
 * clear. There is deliberately no {@code from(PayoutAccount)} factory: constructing straight off
 * the entity would surface ciphertext, so ProfileService owns the decrypt step.
 */
public record PayoutAccountResponse(String accountHolderName, String upiVpa) {
}
