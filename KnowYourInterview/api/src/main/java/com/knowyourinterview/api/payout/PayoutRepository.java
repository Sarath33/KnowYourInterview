package com.knowyourinterview.api.payout;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByStatusInOrderByCreatedAtAsc(List<Payout.Status> statuses);

    List<Payout> findByContributorIdOrderByCreatedAtDesc(UUID contributorId);

    boolean existsByExperienceId(UUID experienceId);

    // COALESCE(...,0) so a contributor with no matching payouts sums to 0 rather than null —
    // lets these return a primitive long and keeps the profile earnings math total-free of
    // null checks.
    @Query("""
            SELECT COALESCE(SUM(p.amountPaise), 0) FROM Payout p
            WHERE p.contributorId = :contributorId AND p.status = :status
            """)
    long sumAmountByContributorIdAndStatus(
            @Param("contributorId") UUID contributorId, @Param("status") Payout.Status status);

    @Query("""
            SELECT COALESCE(SUM(p.amountPaise), 0) FROM Payout p
            WHERE p.contributorId = :contributorId AND p.status IN :statuses
            """)
    long sumAmountByContributorIdAndStatusIn(
            @Param("contributorId") UUID contributorId, @Param("statuses") List<Payout.Status> statuses);
}
