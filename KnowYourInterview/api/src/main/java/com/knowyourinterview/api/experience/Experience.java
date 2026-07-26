package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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
    private long pricePaise;

    // Set only for admin-authored "reference a public source" submissions — see
    // ExperienceService#createDraft, which is the only writer of these three fields (they
    // aren't part of the regular edit form / applyEdits, so they're immutable after
    // creation). sourceUrl non-null is what makes an experience a "reference" one;
    // isFree/pricePaise=0 follow automatically from that at creation time.
    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "is_free", nullable = false)
    private boolean free;

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

    // Optimistic-lock guard against lost updates (concurrent edit/publish/unpublish on the
    // same experience). Backed by the "version" column added in V4.
    @Version
    @Column(nullable = false)
    private long version;

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
            long pricePaise) {
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

    /** Marks a newly-created experience as an admin-authored reference to an already-public
     * writeup — always free. Called only from ExperienceService#createDraft, right after
     * construction, when the incoming request has a sourceUrl; never used on an existing
     * (already-submitted) experience — see ExperienceRequest's Javadoc on why sourceUrl/
     * sourceName aren't part of the regular edit path. */
    public void markAsReference(String sourceUrl, String sourceName) {
        this.sourceUrl = sourceUrl;
        this.sourceName = sourceName;
        this.free = true;
    }

    /** Marks a newly-created experience as a contributor's own free, unreviewed submission —
     * see ExperienceService#createDraft (sets this when the request opts in) and
     * #submitForReview (skips PENDING_REVIEW entirely and publishes immediately for these).
     * Deliberately leaves sourceUrl/sourceName null, which is what distinguishes this from an
     * admin "reference a public source" submission (also free, but still reviewed normally) —
     * see isSelfFreeContribution(). */
    public void markAsFreeContribution() {
        this.free = true;
    }

    /** True for a contributor's own free submission that skips admin review — as opposed to
     * an admin-authored "reference a public source" submission, which is also free but still
     * goes through the normal review pipeline. The two are told apart by sourceUrl: a
     * reference submission always has one, a self free-contribution never does. */
    public boolean isSelfFreeContribution() {
        return free && sourceUrl == null;
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

    /** Also used to resubmit a REJECTED draft — clears the stale rejection reason so a
     * fresh admin review isn't shown last time's verdict. */
    public void markPendingReview() {
        this.status = ExperienceStatus.PENDING_REVIEW;
        this.rejectionReason = null;
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

    /** Pulls a live listing back to DRAFT so it can be edited and resubmitted through
     * review again. Existing purchasers keep full access regardless — see
     * ExperienceService#getPublicView, which grants access on entitlement/ownership
     * independent of current status. */
    public void unpublish() {
        this.status = ExperienceStatus.DRAFT;
        this.publishedAt = null;
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

    public long getPricePaise() {
        return pricePaise;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getSourceName() {
        return sourceName;
    }

    public boolean isFree() {
        return free;
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
