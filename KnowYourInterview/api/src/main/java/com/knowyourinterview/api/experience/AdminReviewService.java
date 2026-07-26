package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.payout.Payout;
import com.knowyourinterview.api.payout.PayoutRepository;

@Service
public class AdminReviewService {

    private final ExperienceRepository experienceRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final PayoutRepository payoutRepository;
    private final ExperienceResponseAssembler responseAssembler;
    private final long contributorPayoutPaise;

    public AdminReviewService(
            ExperienceRepository experienceRepository,
            ReviewLogRepository reviewLogRepository,
            PayoutRepository payoutRepository,
            ExperienceResponseAssembler responseAssembler,
            @Value("${app.pricing.contributor-payout-paise}") long contributorPayoutPaise) {
        this.experienceRepository = experienceRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.payoutRepository = payoutRepository;
        this.responseAssembler = responseAssembler;
        this.contributorPayoutPaise = contributorPayoutPaise;
    }

    @Transactional(readOnly = true)
    public List<ExperienceFullResponse> reviewQueue() {
        // Batched build — one query each for rounds/proofs/unlock-counts across the queue,
        // instead of three per pending experience.
        return responseAssembler.buildMany(
                experienceRepository.findByStatusOrderByCreatedAtAsc(ExperienceStatus.PENDING_REVIEW));
    }

    @Transactional(readOnly = true)
    public ExperienceFullResponse getForReview(UUID experienceId) {
        return responseAssembler.toFullResponse(getOrThrow(experienceId));
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
        // RazorpayX transfer — see Payout.java and PayoutService for why. Skipped for a free
        // (admin "reference a public source") experience — there's no revenue behind it, so
        // nothing to pay the contributor out of. Free, unreviewed contributions never reach
        // this method at all (they publish straight from submitForReview), but a reference
        // submission does still go through review, so this guard is the one place that would
        // otherwise create a payout for $0 of actual sales.
        if (!experience.isFree()) {
            payoutRepository.save(new Payout(
                    UUID.randomUUID(), experienceId, experience.getContributorId(), contributorPayoutPaise));
        }

        return responseAssembler.toFullResponse(experience);
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

        return responseAssembler.toFullResponse(experience);
    }

    private Experience getOrThrow(UUID experienceId) {
        return experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
    }
}
