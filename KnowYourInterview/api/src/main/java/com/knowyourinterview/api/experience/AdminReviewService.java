package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;

@Service
public class AdminReviewService {

    private final ExperienceRepository experienceRepository;
    private final ExperienceRoundRepository roundRepository;
    private final ProofDocumentRepository proofDocumentRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final PayoutRepository payoutRepository;
    private final int contributorPayoutPaise;

    public AdminReviewService(
            ExperienceRepository experienceRepository,
            ExperienceRoundRepository roundRepository,
            ProofDocumentRepository proofDocumentRepository,
            ReviewLogRepository reviewLogRepository,
            PayoutRepository payoutRepository,
            @Value("${app.pricing.contributor-payout-paise}") int contributorPayoutPaise) {
        this.experienceRepository = experienceRepository;
        this.roundRepository = roundRepository;
        this.proofDocumentRepository = proofDocumentRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.payoutRepository = payoutRepository;
        this.contributorPayoutPaise = contributorPayoutPaise;
    }

    @Transactional(readOnly = true)
    public List<ExperienceFullResponse> reviewQueue() {
        return experienceRepository.findByStatusOrderByCreatedAtAsc(ExperienceStatus.PENDING_REVIEW).stream()
                .map(this::toFullResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExperienceFullResponse getForReview(UUID experienceId) {
        return toFullResponse(getOrThrow(experienceId));
    }

    @Transactional
    public ExperienceFullResponse approve(UUID adminId, UUID experienceId) {
        Experience experience = getOrThrow(experienceId);
        if (experience.getStatus() != ExperienceStatus.PENDING_REVIEW) {
            throw new InvalidStateException("Only a pending-review experience can be approved");
        }

        experience.publish();
        experienceRepository.save(experience);

        reviewLogRepository.save(new ReviewLog(
                UUID.randomUUID(), experienceId, adminId, ReviewLog.Action.APPROVED, null));

        // Creates the ledger row at PENDING. Money movement itself is a manual batch
        // process (admin wires it themselves, then marks it paid) rather than a live
        // RazorpayX transfer — see Payout.java and PayoutService for why.
        payoutRepository.save(new Payout(
                UUID.randomUUID(), experienceId, experience.getContributorId(), contributorPayoutPaise));

        return toFullResponse(experience);
    }

    @Transactional
    public ExperienceFullResponse reject(UUID adminId, UUID experienceId, String reason) {
        Experience experience = getOrThrow(experienceId);
        if (experience.getStatus() != ExperienceStatus.PENDING_REVIEW) {
            throw new InvalidStateException("Only a pending-review experience can be rejected");
        }

        experience.reject(reason);
        experienceRepository.save(experience);

        reviewLogRepository.save(new ReviewLog(
                UUID.randomUUID(), experienceId, adminId, ReviewLog.Action.REJECTED, reason));

        return toFullResponse(experience);
    }

    private Experience getOrThrow(UUID experienceId) {
        return experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
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
}
