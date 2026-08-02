package com.knowyourinterview.api.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertPayoutAccountRequest(
        @NotBlank @Size(max = 255) String accountHolderName,
        // Strict UPI VPA shape check: handle@psp. The handle is 2-256 chars of letters/digits/
        // dot/hyphen/underscore; the PSP must start with a letter and be 2-64 chars of letters/
        // digits. Case is normalized (lowercased) in ProfileService before storage, since VPAs
        // are case-insensitive. @Size(max) bounds the whole string ahead of the @Pattern; 321 is
        // the pattern's own maximum (256 + '@' + 64). Real deliverability is still only proven
        // when the admin actually sends the money.
        @NotBlank @Size(max = 321)
        @Pattern(
                regexp = "^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$",
                message = "Enter a valid UPI ID, e.g. name@bank.")
        String upiVpa) {
}
