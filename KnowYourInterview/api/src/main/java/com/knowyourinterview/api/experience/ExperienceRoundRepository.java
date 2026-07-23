package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExperienceRoundRepository extends JpaRepository<ExperienceRound, UUID> {

    List<ExperienceRound> findByExperienceIdOrderByRoundNumberAsc(UUID experienceId);

    Optional<ExperienceRound> findByIdAndExperienceId(UUID id, UUID experienceId);

    long countByExperienceId(UUID experienceId);

    void deleteByIdAndExperienceId(UUID id, UUID experienceId);

    void deleteByExperienceId(UUID experienceId);

    /** Bulk round-count lookup for a page of experiences (browse listing) — one query
     * instead of one-per-card. Experiences with zero rounds simply don't appear in the
     * result, so callers should default missing ids to 0. */
    @Query("""
            SELECT r.experienceId AS experienceId, COUNT(r) AS roundCount
            FROM ExperienceRound r
            WHERE r.experienceId IN :experienceIds
            GROUP BY r.experienceId
            """)
    List<ExperienceRoundCount> countByExperienceIdIn(@Param("experienceIds") List<UUID> experienceIds);

    interface ExperienceRoundCount {
        UUID getExperienceId();

        long getRoundCount();
    }
}
