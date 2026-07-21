package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    List<Payout> findByStatusInOrderByCreatedAtAsc(List<Payout.Status> statuses);

    List<Payout> findByContributorIdOrderByCreatedAtDesc(UUID contributorId);
}
