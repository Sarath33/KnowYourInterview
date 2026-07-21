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
@Table(name = "experiences")
public class Experience {

    @Id
    private UUID id;

    @Column(name = "contributor_id", nullable = false)
    private UUID contributorId;

    @Column(nullable = false)
    private String company;

    @Column(name = "role_title", nullable = false)
    private String roleTitle;

    private String level;

    private String location;

    @Column(name = "is_remote", nullable = false)
    private boolean remote;

    @Column(name = "interview_month")
    private Short interviewMonth;

    @Column(name = "interview_year")
    private Short interviewYear;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ExperienceOutcome outcome;

    @Column(nullable = false, columnDefinition = "text")
    private String teaser;

    @Column(name = "prep_advice", columnDefinition = "text")
    private String prepAdvice;

    @Column(name = "overall_difficulty")
    private Short overallDifficulty;

    private String timeline;

    private String compensation;

    @Column(name = "price_paise", nullable = false)
    private int pricePaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ExperienceStatus status;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    // Rounds and proof documents are separate aggregates, queried by experienceId via
    // their own repositories rather than mapped as JPA associations here — keeps this
    // entity simple and avoids lazy-loading surprises. See ExperienceService.

    protected Experience() {
        // JPA
    }

    public Experience(
            UUID id,
            UUID contributorId,
            String company,
            String roleTitle,
            String level,
            String location,
            boolean remote,
            Short interviewMonth,
            Short interviewYear,
            ExperienceOutcome outcome,
            String teaser,
            String prepAdvice,
            Short overallDifficulty,
            String timeline,
            String compensation,
            int pricePaise) {
        this.id = id;
        this.contributorId = contributorId;
        this.company = company;
        this.roleTitle = roleTitle;
        this.level = level;
        this.location = location;
        this.remote = remote;
        this.interviewMonth = interviewMonth;
        this.interviewYear = interviewYear;
        this.outcome = outcome;
        this.teaser = teaser;
        this.prepAdvice = prepAdvice;
        this.overallDifficulty = overallDifficulty;
        this.timeline = timeline;
        this.compensation = compensation;
        this.pricePaise = pricePaise;
        this.status = ExperienceStatus.DRAFT;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void applyEdits(
            String company,
            String roleTitle,
            String level,
            String location,
            boolean remote,
            Short interviewMonth,
            Short interviewYear,
            ExperienceOutcome outcome,
            String teaser,
            String prepAdvice,
            Short overallDifficulty,
            String timeline,
            String compensation) {
        this.company = company;
        this.roleTitle = roleTitle;
        this.level = level;
        this.location = location;
        this.remote = remote;
        this.interviewMonth = interviewMonth;
        this.interviewYear = interviewYear;
        this.outcome = outcome;
        this.teaser = teaser;
        this.prepAdvice = prepAdvice;
        this.overallDifficulty = overallDifficulty;
        this.timeline = timeline;
        this.compensation = compensation;
        this.updatedAt = Instant.now();
    }

    public void markPendingReview() {
        this.status = ExperienceStatus.PENDING_REVIEW;
        this.updatedAt = Instant.now();
    }

    public void publish() {
        this.status = ExperienceStatus.PUBLISHED;
        Instant now = Instant.now();
        this.updatedAt = now;
        this.publishedAt = now;
    }

    public void reject(String reason) {
        this.status = ExperienceStatus.REJECTED;
        this.rejectionReason = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getContributorId() {
        return contributorId;
    }

    public String getCompany() {
        return company;
    }

    public String getRoleTitle() {
        return roleTitle;
    }

    public String getLevel() {
        return level;
    }

    public String getLocation() {
        return location;
    }

    public boolean isRemote() {
        return remote;
    }

    public Short getInterviewMonth() {
        return interviewMonth;
    }

    public Short getInterviewYear() {
        return interviewYear;
    }

    public ExperienceOutcome getOutcome() {
        return outcome;
    }

    public String getTeaser() {
        return teaser;
    }

    public String getPrepAdvice() {
        return prepAdvice;
    }

    public Short getOverallDifficulty() {
        return overallDifficulty;
    }

    public String getTimeline() {
        return timeline;
    }

    public String getCompensation() {
        return compensation;
    }

    public int getPricePaise() {
        return pricePaise;
    }

    public ExperienceStatus getStatus() {
        return status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
