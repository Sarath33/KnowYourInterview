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
 * Ledger record created when an experience is approved/published. Money movement is
 * currently a manual batch process, not a live RazorpayX transfer: RazorpayX needs a
 * separate Current Account with its own business KYC approval, which isn't set up.
 * An admin wires the contributor's flat fee themselves (bank transfer/UPI) and then
 * marks this row PAID via markPaid(), optionally recording a reference (e.g. a UPI
 * transaction ID) for their own reconciliation. Swapping in a real RazorpayX Payout
 * call later means adding a razorpayxPayoutId-setting path alongside this one — the
 * PENDING/PROCESSING/PAID/FAILED status model was chosen so that swap doesn't require
 * a schema change.
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

    @Column(name = "paid_by_admin_id")
    private UUID paidByAdminId;

    @Column(name = "payout_reference")
    private String payoutReference;

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

    public void markPaid(UUID adminId, String reference) {
        this.status = Status.PAID;
        this.paidByAdminId = adminId;
        this.payoutReference = reference;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public UUID getContributorId() {
        return contributorId;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public String getRazorpayxPayoutId() {
        return razorpayxPayoutId;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getPaidByAdminId() {
        return paidByAdminId;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
