package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ledger record created when an experience is approved/published. Actual money
 * movement (RazorpayX transfer) is Phase 4 — for now this just records that a
 * payout is owed, at PENDING status, so nothing about the ledger needs to change
 * once Razorpay integration lands.
 */
@Entity
@Table(name = "payouts")
public class Payout {

    public enum Status {
        PENDING,
        PROCESSING,
        PAID,
        FAILED
    }

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false, unique = true)
    private UUID experienceId;

    @Column(name = "contributor_id", nullable = false)
    private UUID contributorId;

    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Column(name = "razorpayx_payout_id")
    private String razorpayxPayoutId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payout() {
        // JPA
    }

    public Payout(UUID id, UUID experienceId, UUID contributorId, int amountPaise) {
        this.id = id;
        this.experienceId = experienceId;
        this.contributorId = contributorId;
        this.amountPaise = amountPaise;
        this.status = Status.PENDING;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }
}
