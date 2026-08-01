package com.knowyourinterview.api.experience;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceViewRepository extends JpaRepository<ExperienceView, UUID> {

    /**
     * Records a viewer's view of an experience, ignoring the insert if this exact
     * (experience, viewer) pair was already recorded — ON CONFLICT DO NOTHING is what
     * makes this atomic and race-safe at the database level (no read-then-write gap for
     * two near-simultaneous requests to fall through), unlike a
     * findExists-then-insert pattern in application code. Returns 1 if this was a genuinely
     * new view, 0 if it was a duplicate — the caller (ExperienceService#getPublicView)
     * only bumps Experience#viewCount when this returns 1.
     */
    @Modifying
    @Query(value = """
            INSERT INTO experience_views (id, experience_id, viewer_id, viewed_at)
            VALUES (:id, :experienceId, :viewerId, now())
            ON CONFLICT (experience_id, viewer_id) DO NOTHING
            """, nativeQuery = true)
    int recordView(@Param("id") UUID id, @Param("experienceId") UUID experienceId, @Param("viewerId") UUID viewerId);

    /** Cleanup for ExperienceService#deleteExperience. experience_views' foreign key to
     * experiences deliberately doesn't cascade (see V10), so these rows have to be removed
     * explicitly alongside rounds/proofs/review logs/edit snapshots — otherwise deleting an
     * experience that anyone ever viewed fails on the constraint and surfaces as an opaque
     * 409. Reachable in practice for a free or reference submission (no entitlement and no
     * payout to block the delete earlier) that was published, viewed, then unpublished. */
    void deleteByExperienceId(UUID experienceId);
}
