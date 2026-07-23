package com.knowyourinterview.api.experience;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewLogRepository extends JpaRepository<ReviewLog, UUID> {

    void deleteByExperienceId(UUID experienceId);
}
