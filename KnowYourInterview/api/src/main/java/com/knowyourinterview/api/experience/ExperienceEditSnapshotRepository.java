package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExperienceEditSnapshotRepository extends JpaRepository<ExperienceEditSnapshot, UUID> {

    List<ExperienceEditSnapshot> findByExperienceIdOrderByRecordedAtDesc(UUID experienceId);

    void deleteByExperienceId(UUID experienceId);
}
