package com.knowyourinterview.api.experience;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.payment.EntitlementRepository;

/**
 * Builds {@link ExperienceFullResponse}s from an {@link Experience} plus its rounds,
 * proof documents, and unlock count. Extracted so ExperienceService and AdminReviewService
 * share one implementation instead of each carrying a private copy (previously duplicated).
 *
 * <p>Two entry points: {@link #toFullResponse(Experience)} for the single-experience paths
 * (create/edit/approve/reject/view), and {@link #buildMany(List)} which fetches rounds,
 * proofs, and unlock counts in one batched query each across a whole list — mirroring the
 * batching in ExperienceService.browsePublished — so list endpoints don't fan out into
 * N per-row queries.
 */
@Component
public class ExperienceResponseAssembler {

    private final ExperienceRoundRepository roundRepository;
    private final ProofDocumentRepository proofDocumentRepository;
    private final EntitlementRepository entitlementRepository;

    public ExperienceResponseAssembler(
            ExperienceRoundRepository roundRepository,
            ProofDocumentRepository proofDocumentRepository,
            EntitlementRepository entitlementRepository) {
        this.roundRepository = roundRepository;
        this.proofDocumentRepository = proofDocumentRepository;
        this.entitlementRepository = entitlementRepository;
    }

    /** Single-experience full response — three small per-id lookups. Includes
     * confidentialNote — use the other overload for a viewer who isn't the owner or an
     * admin (see ExperienceService#getPublicView, the one caller that isn't always
     * owner/admin). */
    public ExperienceFullResponse toFullResponse(Experience experience) {
        return toFullResponse(experience, true);
    }

    public ExperienceFullResponse toFullResponse(Experience experience, boolean includeConfidentialNote) {
        List<ExperienceRoundResponse> rounds = roundRepository
                .findByExperienceIdOrderByRoundNumberAsc(experience.getId()).stream()
                .map(ExperienceRoundResponse::from)
                .toList();
        List<ProofDocumentResponse> proof = proofDocumentRepository
                .findByExperienceId(experience.getId()).stream()
                .map(ProofDocumentResponse::from)
                .toList();
        long unlockCount = entitlementRepository.countByExperienceId(experience.getId());
        return ExperienceFullResponse.from(experience, rounds, proof, unlockCount, includeConfidentialNote);
    }

    /**
     * Batched multi-experience build: one query each for rounds, proofs, and unlock counts
     * across the whole list, instead of the 3-per-row the single path would do. Preserves
     * the input ordering.
     */
    public List<ExperienceFullResponse> buildMany(List<Experience> experiences) {
        if (experiences.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = experiences.stream().map(Experience::getId).toList();

        Map<UUID, List<ExperienceRoundResponse>> roundsById = roundRepository
                .findByExperienceIdInOrderByExperienceIdAscRoundNumberAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        ExperienceRound::getExperienceId, LinkedHashMap::new,
                        Collectors.mapping(ExperienceRoundResponse::from, Collectors.toList())));

        Map<UUID, List<ProofDocumentResponse>> proofsById = proofDocumentRepository
                .findByExperienceIdIn(ids).stream()
                .collect(Collectors.groupingBy(
                        ProofDocument::getExperienceId, LinkedHashMap::new,
                        Collectors.mapping(ProofDocumentResponse::from, Collectors.toList())));

        Map<UUID, Long> unlockCountsById = entitlementRepository.countByExperienceIdIn(ids).stream()
                .collect(Collectors.toMap(
                        EntitlementRepository.ExperienceUnlockCount::getExperienceId,
                        EntitlementRepository.ExperienceUnlockCount::getUnlockCount));

        return experiences.stream()
                .map(e -> ExperienceFullResponse.from(
                        e,
                        roundsById.getOrDefault(e.getId(), List.of()),
                        proofsById.getOrDefault(e.getId(), List.of()),
                        unlockCountsById.getOrDefault(e.getId(), 0L)))
                .toList();
    }
}
