package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceRepository extends JpaRepository<Experience, UUID> {

    List<Experience> findByContributorIdOrderByCreatedAtDesc(UUID contributorId);

    List<Experience> findByStatusOrderByCreatedAtAsc(ExperienceStatus status);

    // The CAST(:param AS string) on each nullable filter isn't decorative — Postgres plans
    // the whole WHERE clause up front, including the LOWER(:param) branch, even though the
    // ":param IS NULL OR ..." check means it's never evaluated at runtime for a null filter.
    // Without an explicit type, a null parameter arrives with no type info and LOWER() can't
    // resolve an overload for it ("function lower(bytea) does not exist"). The cast fixes that.
    // searchPattern arrives pre-wrapped with "%...%" wildcards from the service (already
    // lowercased) rather than built with CONCAT/LOWER here — keeps this query symmetric
    // with the other filters' null-check style and avoids a CONCAT-inside-CAST mess.
    @Query("""
            SELECT e FROM Experience e
            WHERE e.status = com.knowyourinterview.api.experience.ExperienceStatus.PUBLISHED
              AND (:company IS NULL OR LOWER(e.company) = LOWER(CAST(:company AS string)))
              AND (:roleTitle IS NULL OR LOWER(e.roleTitle) = LOWER(CAST(:roleTitle AS string)))
              AND (:level IS NULL OR LOWER(e.level) = LOWER(CAST(:level AS string)))
              AND (:year IS NULL OR e.interviewYear = :year)
              AND (:isFree IS NULL OR e.free = :isFree)
              AND (:searchPattern IS NULL
                   OR LOWER(e.company) LIKE CAST(:searchPattern AS string)
                   OR LOWER(e.roleTitle) LIKE CAST(:searchPattern AS string)
                   OR LOWER(e.teaser) LIKE CAST(:searchPattern AS string))
            """)
    Page<Experience> browsePublished(
            @Param("company") String company,
            @Param("roleTitle") String roleTitle,
            @Param("level") String level,
            @Param("year") Short year,
            @Param("isFree") Boolean isFree,
            @Param("searchPattern") String searchPattern,
            Pageable pageable);

    /**
     * Bumps view_count in the database rather than through the managed entity, and
     * deliberately leaves the optimistic-lock `version` column alone.
     * <p>
     * The previous approach — {@code experience.incrementViewCount()} followed by a save —
     * went through JPA's versioned UPDATE, so two <em>different</em> viewers opening the
     * same experience for the first time concurrently would collide on {@code @Version} and
     * one of them would get a 409 on what is, from their side, an ordinary page load.
     * ExperienceView's ON CONFLICT DO NOTHING de-dupes the same viewer, but does nothing for
     * two distinct ones. An unversioned atomic increment can't lose an update or conflict:
     * the database serializes the two statements and both are counted.
     * <p>
     * Not bumping `version` is the point, not an oversight — a view is not a content edit,
     * and letting it invalidate a concurrent editor's in-flight update would be a worse
     * trade than the lost-update protection it nominally buys on a counter that's already
     * append-only.
     * <p>
     * clearAutomatically evicts the now-stale managed Experience so the caller's re-read
     * (see ExperienceService#getPublicView) returns the incremented value instead of the
     * cached one.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE experiences SET view_count = view_count + 1 WHERE id = :id", nativeQuery = true)
    void incrementViewCount(@Param("id") UUID id);
}
