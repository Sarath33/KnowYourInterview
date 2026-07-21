package com.knowyourinterview.api.payment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementRepository extends JpaRepository<Entitlement, UUID> {

    boolean existsByUserIdAndExperienceId(UUID userId, UUID experienceId);

    List<Entitlement> findByUserIdOrderByGrantedAtDesc(UUID userId);
}
