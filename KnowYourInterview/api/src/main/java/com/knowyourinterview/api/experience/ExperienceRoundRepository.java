package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperienceRoundRepository extends JpaRepository<ExperienceRound, UUID> {

    List<ExperienceRound> findByExperienceIdOrderByRoundNumberAsc(UUID experienceId);

    long countByExperienceId(UUID experienceId);

    void deleteByIdAndExperienceId(UUID id, UUID experienceId);
}
