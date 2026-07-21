package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProofDocumentRepository extends JpaRepository<ProofDocument, UUID> {

    List<ProofDocument> findByExperienceId(UUID experienceId);

    long countByExperienceId(UUID experienceId);

    Optional<ProofDocument> findByIdAndExperienceId(UUID id, UUID experienceId);
}
