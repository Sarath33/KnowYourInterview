package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_logs")
public class ReviewLog {

    public enum Action {
        APPROVED,
        REJECTED
    }

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Action action;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewLog() {
        // JPA
    }

    public ReviewLog(UUID id, UUID experienceId, UUID adminId, Action action, String reason) {
        this.id = id;
        this.experienceId = experienceId;
        this.adminId = adminId;
        this.action = action;
        this.reason = reason;
        this.createdAt = Instant.now();
    }
}
