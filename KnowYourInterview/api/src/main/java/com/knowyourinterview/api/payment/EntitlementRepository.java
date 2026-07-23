package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

    boolean existsByUserIdAndExperienceId(UUID userId, UUID experienceId);

    boolean existsByExperienceId(UUID experienceId);

    long countByExperienceId(UUID experienceId);

    List<Entitlement> findByUserIdOrderByGrantedAtDesc(UUID userId);

    // Bulk "which of these experiences has this user already unlocked" lookup for Browse
    // — avoids an existsByUserIdAndExperienceId round trip per card on the page.
    @Query("SELECT e.experienceId FROM Entitlement e WHERE e.userId = :userId AND e.experienceId IN :experienceIds")
    List<UUID> findExperienceIdsByUserIdAndExperienceIdIn(
            @Param("userId") UUID userId, @Param("experienceIds") List<UUID> experienceIds);
}
