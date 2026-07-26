package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A snapshot of an experience's top-level editable fields, saved right before each real
 * edit (see ExperienceService#updateDraft) — captures what it looked like just before
 * that edit overwrote it. Ordered by recordedAt, the sequence of snapshots plus the
 * current live experience reconstructs the full edit history. Rounds aren't included
 * here — they have their own edit-in-place UI and per-round history would be a separate,
 * much bigger feature; this is scoped to the fields EditDetailsForm actually edits. */
@Entity
@Table(name = "experience_edit_snapshots")
public class ExperienceEditSnapshot {

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

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

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ExperienceEditSnapshot() {
        // JPA
    }

    /** Copies the given experience's current field values — call this right before
     * applying an edit to it, not after. */
    public ExperienceEditSnapshot(UUID id, Experience experience) {
        this.id = id;
        this.experienceId = experience.getId();
        this.company = experience.getCompany();
        this.roleTitle = experience.getRoleTitle();
        this.level = experience.getLevel();
        this.location = experience.getLocation();
        this.remote = experience.isRemote();
        this.interviewMonth = experience.getInterviewMonth();
        this.interviewYear = experience.getInterviewYear();
        this.outcome = experience.getOutcome();
        this.teaser = experience.getTeaser();
        this.prepAdvice = experience.getPrepAdvice();
        this.overallDifficulty = experience.getOverallDifficulty();
        this.timeline = experience.getTimeline();
        this.compensation = experience.getCompensation();
        this.recordedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
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

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
