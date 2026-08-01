package com.knowyourinterview.api.experience;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per (experience, signed-in viewer) pair — the DB-level UNIQUE(experience_id,
 * viewer_id) constraint (see V10 migration) is what makes {@link Experience#viewCount}
 * "one per user" rather than incrementing on every page load. Rows are only ever inserted
 * via {@link ExperienceViewRepository#recordView}, which uses INSERT ... ON CONFLICT DO
 * NOTHING so a duplicate view (a repeat visit, or two near-simultaneous requests for the
 * same viewer — e.g. React StrictMode's double-invoked effects in dev) is a no-op rather
 * than a constraint-violation exception. Guests (no account) are never recorded — there's
 * no reliable identity to dedupe a guest against without adding session/cookie tracking.
 * No setters; a view, once recorded, never changes.
 */
@Entity
@Table(name = "experience_views")
public class ExperienceView {

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "viewer_id", nullable = false)
    private UUID viewerId;

    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    protected ExperienceView() {
        // JPA
    }

    public ExperienceView(UUID id, UUID experienceId, UUID viewerId) {
        this.id = id;
        this.experienceId = experienceId;
        this.viewerId = viewerId;
        this.viewedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public UUID getViewerId() {
        return viewerId;
    }

    public Instant getViewedAt() {
        return viewedAt;
    }
}
