package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProofDocumentRepository extends JpaRepository<ProofDocument, UUID> {

    List<ProofDocument> findByExperienceId(UUID experienceId);

    /** Batched variant for building many experiences at once (see
     * ExperienceResponseAssembler#buildMany). */
    List<ProofDocument> findByExperienceIdIn(List<UUID> experienceIds);

    long countByExperienceId(UUID experienceId);

    Optional<ProofDocument> findByIdAndExperienceId(UUID id, UUID experienceId);
}
