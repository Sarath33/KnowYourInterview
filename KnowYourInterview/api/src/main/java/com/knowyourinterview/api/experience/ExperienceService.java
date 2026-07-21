package com.knowyourinterview.api.experience;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.common.PagedResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceTeaserResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.payment.EntitlementRepository;

@Service
public class ExperienceService {

    private final ExperienceRepository experienceRepository;
    private final ExperienceRoundRepository roundRepository;
    private final ProofDocumentRepository proofDocumentRepository;
    private final ProofStorageService proofStorageService;
    private final EntitlementRepository entitlementRepository;
    private final int defaultPricePaise;

    public ExperienceService(
            ExperienceRepository experienceRepository,
            ExperienceRoundRepository roundRepository,
            ProofDocumentRepository proofDocumentRepository,
            ProofStorageService proofStorageService,
            EntitlementRepository entitlementRepository,
            @Value("${app.pricing.default-price-paise}") int defaultPricePaise) {
        this.experienceRepository = experienceRepository;
        this.roundRepository = roundRepository;
        this.proofDocumentRepository = proofDocumentRepository;
        this.proofStorageService = proofStorageService;
        this.entitlementRepository = entitlementRepository;
        this.defaultPricePaise = defaultPricePaise;
    }

    @Transactional
    public ExperienceFullResponse createDraft(UUID contributorId, ExperienceRequest req) {
        Experience experience = new Experience(
                UUID.randomUUID(), contributorId, req.company(), req.roleTitle(), req.level(), req.location(),
                req.isRemote(), req.interviewMonth(), req.interviewYear(), req.outcome(), req.teaser(),
                req.prepAdvice(), req.overallDifficulty(), req.timeline(), req.compensation(), defaultPricePaise);
        experienceRepository.save(experience);
        return toFullResponse(experience);
    }

    @Transactional
    public ExperienceFullResponse updateDraft(UUID contributorId, UUID experienceId, ExperienceRequest req) {
        Experience experience = getOwned(contributorId, experienceId);
        requireStatus(experience, ExperienceStatus.DRAFT, "Only draft experiences can be edited");
        experience.applyEdits(
                req.company(), req.roleTitle(), req.level(), req.location(), req.isRemote(), req.interviewMonth(),
                req.interviewYear(), req.outcome(), req.teaser(), req.prepAdvice(), req.overallDifficulty(),
                req.timeline(), req.compensation());
        experienceRepository.save(experience);
        return toFullResponse(experience);
    }

    @Transactional
    public ExperienceRoundResponse addRound(UUID contributorId, UUID experienceId, RoundRequest req) {
        Experience experience = getOwned(contributorId, experienceId);
        requireStatus(experience, ExperienceStatus.DRAFT, "Rounds can only be added to a draft");
        short nextNumber = (short) (roundRepository.countByExperienceId(experienceId) + 1);
        ExperienceRound round = new ExperienceRound(
                UUID.randomUUID(), experienceId, nextNumber, req.roundType(), req.durationMinutes(),
                req.questionsAsked(), joinTags(req.topicsTags()), req.approach(), req.interviewerBehavior(),
                req.difficulty());
        roundRepository.save(round);
        return ExperienceRoundResponse.from(round);
    }

    @Transactional
    public void deleteRound(UUID contributorId, UUID experienceId, UUID roundId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireStatus(experience, ExperienceStatus.DRAFT, "Rounds can only be removed from a draft");
        roundRepository.deleteByIdAndExperienceId(roundId, experienceId);
    }

    @Transactional
    public ProofDocumentResponse uploadProof(UUID contributorId, UUID experienceId, MultipartFile file) {
        Experience experience = getOwned(contributorId, experienceId);
        requireStatus(experience, ExperienceStatus.DRAFT, "Proof can only be uploaded while the experience is a draft");
        if (file.isEmpty()) {
            throw new InvalidStateException("Uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            ProofStorageService.StoredFile stored = proofStorageService.store(experienceId, file.getOriginalFilename(), in);
            ProofDocument proof = new ProofDocument(
                    UUID.randomUUID(), experienceId, stored.storageKey(), file.getOriginalFilename(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType());
            proofDocumentRepository.save(proof);
            return ProofDocumentResponse.from(proof);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    @Transactional
    public ExperienceFullResponse submitForReview(UUID contributorId, UUID experienceId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireStatus(experience, ExperienceStatus.DRAFT, "Only a draft can be submitted for review");

        if (roundRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Add at least one interview round before submitting");
        }
        if (proofDocumentRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Upload at least one proof document before submitting");
        }

        experience.markPendingReview();
        experienceRepository.save(experience);
        return toFullResponse(experience);
    }

    @Transactional(readOnly = true)
    public List<ExperienceFullResponse> listMine(UUID contributorId) {
        return experienceRepository.findByContributorIdOrderByCreatedAtDesc(contributorId).stream()
                .map(this::toFullResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExperienceFullResponse getMine(UUID contributorId, UUID experienceId) {
        return toFullResponse(getOwned(contributorId, experienceId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExperienceTeaserResponse> browsePublished(
            String company, String roleTitle, String level, Short year, int page, int size) {
        Page<Experience> result = experienceRepository.browsePublished(
                blankToNull(company), blankToNull(roleTitle), blankToNull(level), year,
                PageRequest.of(page, Math.min(size, 100)));
        return PagedResponse.of(result.map(ExperienceTeaserResponse::from));
    }

    /**
     * Public single-experience view. "entitled" (full content) is true for: the owning
     * contributor, an admin (needs it to review), or a viewer holding a real paid
     * Entitlement row (Phase 4). Everyone else gets the teaser.
     */
    @Transactional(readOnly = true)
    public ExperienceViewResponse getPublicView(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));

        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        if (!isOwner && !viewerIsAdmin && experience.getStatus() != ExperienceStatus.PUBLISHED) {
            throw new NotFoundException("Experience not found");
        }

        boolean hasPurchased = viewerId != null
                && !isOwner
                && !viewerIsAdmin
                && entitlementRepository.existsByUserIdAndExperienceId(viewerId, experienceId);

        if (isOwner || viewerIsAdmin || hasPurchased) {
            return ExperienceViewResponse.fullAccess(toFullResponse(experience));
        }
        return ExperienceViewResponse.teaserOnly(ExperienceTeaserResponse.from(experience));
    }

    public record ProofDownload(ProofDocument document, InputStream content) {}

    @Transactional(readOnly = true)
    public ProofDownload downloadProof(UUID viewerId, boolean viewerIsAdmin, UUID experienceId, UUID proofId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        if (!isOwner && !viewerIsAdmin) {
            throw new ForbiddenException("You don't have access to this document");
        }
        ProofDocument doc = proofDocumentRepository.findByIdAndExperienceId(proofId, experienceId)
                .orElseThrow(() -> new NotFoundException("Proof document not found"));
        return new ProofDownload(doc, proofStorageService.retrieve(doc.getStorageKey()));
    }

    private Experience getOwned(UUID contributorId, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        if (!experience.getContributorId().equals(contributorId)) {
            throw new ForbiddenException("You don't own this experience");
        }
        return experience;
    }

    private void requireStatus(Experience experience, ExperienceStatus required, String message) {
        if (experience.getStatus() != required) {
            throw new InvalidStateException(message);
        }
    }

    private ExperienceFullResponse toFullResponse(Experience experience) {
        List<ExperienceRoundResponse> rounds = roundRepository
                .findByExperienceIdOrderByRoundNumberAsc(experience.getId()).stream()
                .map(ExperienceRoundResponse::from)
                .toList();
        List<ProofDocumentResponse> proof = proofDocumentRepository
                .findByExperienceId(experience.getId()).stream()
                .map(ProofDocumentResponse::from)
                .toList();
        return ExperienceFullResponse.from(experience, rounds, proof);
    }

    private static String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
