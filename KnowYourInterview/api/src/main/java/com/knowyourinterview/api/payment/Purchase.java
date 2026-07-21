package com.knowyourinterview.api.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "purchases")
public class Purchase {

    public enum Status {
        CREATED,
        PAID,
        FAILED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "amount_paise", nullable = false)
    private int amountPaise;

    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Purchase() {
        // JPA
    }

    public Purchase(UUID id, UUID userId, UUID experienceId, int amountPaise, String razorpayOrderId) {
        this.id = id;
        this.userId = userId;
        this.experienceId = experienceId;
        this.amountPaise = amountPaise;
        this.razorpayOrderId = razorpayOrderId;
        this.status = Status.CREATED;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markPaid(String razorpayPaymentId) {
        this.razorpayPaymentId = razorpayPaymentId;
        this.status = Status.PAID;
        this.updatedAt = Instant.now();
    }

    public void markFailed() {
        this.status = Status.FAILED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public int getAmountPaise() {
        return amountPaise;
    }

    public String getRazorpayOrderId() {
        return razorpayOrderId;
    }

    public String getRazorpayPaymentId() {
        return razorpayPaymentId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
