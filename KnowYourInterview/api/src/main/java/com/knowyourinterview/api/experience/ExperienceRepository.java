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

    // Submission count for the profile page — every experience this user has ever submitted,
    // in any status (draft/pending/published/rejected), not just the published ones.
    long countByContributorId(UUID contributorId);

    List<Experience> findByStatusOrderByCreatedAtAsc(ExperienceStatus status);

    // Native (not JPQL) because the relevance ranking needs pg_trgm's similarity() and a
    // computed ORDER BY that Pageable's Sort can't express — the service passes an UNSORTED
    // PageRequest and drives the sort with the :sortMode param instead. Reads the STORED
    // generated columns added in V15 (company_normalized / role_title_normalized /
    // level_normalized), which aren't mapped on the entity; SELECT e.* still returns every
    // mapped column, so Hibernate materializes a full Experience and simply ignores the
    // extra normalized columns.
    //
    // Tier 1 — the company/role/level filters are normalized "contains": both the stored
    // value (the generated column) and the query input (built by the service) are lower-cased
    // with non-alphanumerics stripped, so "SDE-3"/"SDE 3"/"sde3" all match and typing "SDE"
    // returns every SDE level.
    //
    // Tier 2 — search matches on a normalized contains (company/role), a plain lower-cased
    // substring (teaser), OR pg_trgm similarity above :simThreshold, and results are ranked
    // by the best similarity across company/role/teaser (closest first).
    //
    // Every nullable TEXT parameter is CAST(:x AS text): a null bind arrives with no type
    // info, and Postgres otherwise can't type it inside a LIKE/similarity() call. (:year and
    // :isFree don't need it — Postgres infers their type from the compared column.) The
    // countQuery repeats the identical WHERE with no ORDER BY, as Spring Data requires for a
    // native paginated query. e.id is the final tiebreaker so paging is stable when every
    // ranking/sort key ties.
    @Query(value = """
            SELECT e.* FROM experiences e
            WHERE e.status = 'PUBLISHED'
              AND (CAST(:companyPat AS text) IS NULL OR e.company_normalized    LIKE CAST(:companyPat AS text))
              AND (CAST(:rolePat    AS text) IS NULL OR e.role_title_normalized LIKE CAST(:rolePat    AS text))
              AND (CAST(:levelPat   AS text) IS NULL OR e.level_normalized      LIKE CAST(:levelPat   AS text))
              AND (:year IS NULL OR e.interview_year = :year)
              AND (:isFree IS NULL OR e.is_free = :isFree)
              AND (
                CAST(:searchTerm AS text) IS NULL
                OR e.company_normalized    LIKE CAST(:searchContains AS text)
                OR e.role_title_normalized LIKE CAST(:searchContains AS text)
                OR lower(e.teaser)         LIKE CAST(:searchLike AS text)
                OR similarity(lower(e.company),    CAST(:searchTerm AS text)) >= :simThreshold
                OR similarity(lower(e.role_title), CAST(:searchTerm AS text)) >= :simThreshold
                OR similarity(lower(e.teaser),     CAST(:searchTerm AS text)) >= :simThreshold
              )
            ORDER BY
              (CASE WHEN CAST(:searchTerm AS text) IS NOT NULL
                    THEN GREATEST(similarity(lower(e.company),    CAST(:searchTerm AS text)),
                                  similarity(lower(e.role_title), CAST(:searchTerm AS text)),
                                  similarity(lower(e.teaser),     CAST(:searchTerm AS text)))
                    ELSE 0 END) DESC,
              (CASE WHEN :sortMode = 'priceLow'   THEN e.price_paise END) ASC NULLS LAST,
              (CASE WHEN :sortMode = 'priceHigh'  THEN e.price_paise END) DESC NULLS LAST,
              (CASE WHEN :sortMode = 'mostViewed' THEN e.view_count  END) DESC NULLS LAST,
              e.published_at DESC NULLS LAST,
              e.id
            """,
            countQuery = """
            SELECT count(*) FROM experiences e
            WHERE e.status = 'PUBLISHED'
              AND (CAST(:companyPat AS text) IS NULL OR e.company_normalized    LIKE CAST(:companyPat AS text))
              AND (CAST(:rolePat    AS text) IS NULL OR e.role_title_normalized LIKE CAST(:rolePat    AS text))
              AND (CAST(:levelPat   AS text) IS NULL OR e.level_normalized      LIKE CAST(:levelPat   AS text))
              AND (:year IS NULL OR e.interview_year = :year)
              AND (:isFree IS NULL OR e.is_free = :isFree)
              AND (
                CAST(:searchTerm AS text) IS NULL
                OR e.company_normalized    LIKE CAST(:searchContains AS text)
                OR e.role_title_normalized LIKE CAST(:searchContains AS text)
                OR lower(e.teaser)         LIKE CAST(:searchLike AS text)
                OR similarity(lower(e.company),    CAST(:searchTerm AS text)) >= :simThreshold
                OR similarity(lower(e.role_title), CAST(:searchTerm AS text)) >= :simThreshold
                OR similarity(lower(e.teaser),     CAST(:searchTerm AS text)) >= :simThreshold
              )
            """,
            nativeQuery = true)
    Page<Experience> browsePublished(
            @Param("companyPat") String companyPat,
            @Param("rolePat") String rolePat,
            @Param("levelPat") String levelPat,
            @Param("year") Short year,
            @Param("isFree") Boolean isFree,
            @Param("searchTerm") String searchTerm,
            @Param("searchContains") String searchContains,
            @Param("searchLike") String searchLike,
            @Param("simThreshold") double simThreshold,
            @Param("sortMode") String sortMode,
            Pageable pageable);

    // The "did you mean" fallback the frontend fires when a strict browse returns zero rows.
    // Same pg_trgm machinery as browsePublished's Tier 2 (normalized-contains on company/role,
    // a plain lowered substring on the teaser, OR similarity() above a threshold), ranked by the
    // best similarity across company/role/teaser — but DELIBERATELY with none of browse's strict
    // company/role/level/year/isFree filters: this is the query that runs precisely because those
    // filters just matched nothing, so re-imposing them would defeat the point. Uses a lower
    // threshold than search (app.search.suggestion-threshold) so near-misses still surface.
    //
    // Every nullable TEXT param is CAST(:x AS text) for the same reason browsePublished does it —
    // a null bind (e.g. qContains when the query strips to nothing alphanumeric) arrives with no
    // type info and Postgres can't otherwise type it inside LIKE. Returns a plain List capped by
    // LIMIT :limit (no paging, so no Pageable and no countQuery). e.id is the final tiebreaker so
    // the order is deterministic when relevance and published_at tie.
    @Query(value = """
            SELECT e.* FROM experiences e
            WHERE e.status = 'PUBLISHED'
              AND (
                e.company_normalized    LIKE CAST(:qContains AS text)
                OR e.role_title_normalized LIKE CAST(:qContains AS text)
                OR lower(e.teaser)         LIKE CAST(:qLike AS text)
                OR similarity(lower(e.company),    CAST(:qTerm AS text)) >= :threshold
                OR similarity(lower(e.role_title), CAST(:qTerm AS text)) >= :threshold
                OR similarity(lower(e.teaser),     CAST(:qTerm AS text)) >= :threshold
              )
            ORDER BY GREATEST(similarity(lower(e.company),    CAST(:qTerm AS text)),
                              similarity(lower(e.role_title), CAST(:qTerm AS text)),
                              similarity(lower(e.teaser),     CAST(:qTerm AS text))) DESC,
                     e.published_at DESC NULLS LAST,
                     e.id
            LIMIT :limit
            """,
            nativeQuery = true)
    List<Experience> suggestPublished(
            @Param("qContains") String qContains,
            @Param("qLike") String qLike,
            @Param("qTerm") String qTerm,
            @Param("threshold") double threshold,
            @Param("limit") int limit);

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
