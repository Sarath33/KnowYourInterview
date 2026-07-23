package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
            @Param("searchPattern") String searchPattern,
            Pageable pageable);
}
