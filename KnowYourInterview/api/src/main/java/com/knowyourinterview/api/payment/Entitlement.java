package com.knowyourinterview.api.payment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "entitlements")
public class Entitlement {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "purchase_id", nullable = false)
    private UUID purchaseId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    protected Entitlement() {
        // JPA
    }

    public Entitlement(UUID id, UUID userId, UUID experienceId, UUID purchaseId) {
        this.id = id;
        this.userId = userId;
        this.experienceId = experienceId;
        this.purchaseId = purchaseId;
        this.grantedAt = Instant.now();
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

    public UUID getPurchaseId() {
        return purchaseId;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }
}
