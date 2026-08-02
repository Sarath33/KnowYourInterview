package com.knowyourinterview.api.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertPayoutAccountRequest(
        @NotBlank @Size(max = 255) String accountHolderName,
        // Lenient VPA shape check (something@handle) — enough to catch obvious typos without
        // trying to enumerate every valid PSP handle. Real validation happens when the admin
        // sends the money.
        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[\\w.+-]+@[\\w.-]+$", message = "Enter a valid UPI ID, e.g. name@bank")
        String upiVpa) {
}
