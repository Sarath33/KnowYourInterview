package com.knowyourinterview.api.payment.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.payment.Purchase;

public record PurchaseResponse(UUID id, UUID experienceId, int amountPaise, Purchase.Status status, Instant createdAt) {

    public static PurchaseResponse from(Purchase p) {
        return new PurchaseResponse(p.getId(), p.getExperienceId(), p.getAmountPaise(), p.getStatus(), p.getCreatedAt());
    }
}
