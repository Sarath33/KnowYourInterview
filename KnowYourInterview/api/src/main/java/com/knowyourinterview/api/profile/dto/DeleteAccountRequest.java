package com.knowyourinterview.api.profile.dto;

/**
 * password is optional at this layer: a Google-only account has none to confirm with. Whether
 * a password is required — and whether it matches — is enforced in ProfileService against the
 * account's real state. The whole body may also be absent (Google-only delete), so the
 * controller binds it with required = false.
 */
public record DeleteAccountRequest(String password) {
}
