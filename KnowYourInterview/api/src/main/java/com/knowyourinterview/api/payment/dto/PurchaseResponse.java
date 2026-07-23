package com.knowyourinterview.api.payment.dto;

import java.time.Instant;
import java.util.UUID;

import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.payment.Purchase;

/** company/roleTitle/level ride along so My Library can show what was actually
 * bought instead of just a price and a date. experience is looked up in a batch by
 * PurchaseService#listMine; it should never be null in practice — deleteExperience
 * refuses to delete anything with a purchase on record — but the null-safe fallback
 * keeps this endpoint from 500ing if that invariant is ever violated. */
public record PurchaseResponse(
        UUID id, UUID experienceId, String company, String roleTitle, String level, int amountPaise,
        Purchase.Status status, Instant createdAt) {

    public static PurchaseResponse from(Purchase p, Experience experience) {
        return new PurchaseResponse(
                p.getId(),
                p.getExperienceId(),
                experience != null ? experience.getCompany() : "Unknown",
                experience != null ? experience.getRoleTitle() : "Unknown",
                experience != null ? experience.getLevel() : null,
                p.getAmountPaise(),
                p.getStatus(),
                p.getCreatedAt());
    }
}
