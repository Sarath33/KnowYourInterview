package com.knowyourinterview.api.payout;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

/**
 * Manual-batch payouts: RazorpayX needs a separate Current Account with its own
 * business KYC approval, which isn't set up, so an admin wires the contributor's flat
 * fee themselves (bank transfer/UPI) and records it here rather than the app calling a
 * live payout API. See Payout.java and docs/04-handoff.md for the full rationale.
 */
@Service
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final ExperienceRepository experienceRepository;
    private final UserRepository userRepository;

    public PayoutService(
            PayoutRepository payoutRepository, ExperienceRepository experienceRepository,
            UserRepository userRepository) {
        this.payoutRepository = payoutRepository;
        this.experienceRepository = experienceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> queue() {
        List<Payout> payouts = payoutRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(Payout.Status.PENDING, Payout.Status.PROCESSING));
        // Batch the experience + contributor lookups (two queries total) instead of a
        // findById per row (2N queries) — the queue can hold many pending payouts.
        Map<UUID, Experience> experiencesById = experiencesFor(payouts);
        Map<UUID, User> contributorsById = contributorsFor(payouts);
        return payouts.stream()
                .map(payout -> PayoutResponse.forAdmin(
                        payout,
                        requireExperience(experiencesById, payout),
                        requireContributor(contributorsById, payout)))
                .toList();
    }

    @Transactional
    public PayoutResponse markPaid(UUID adminId, UUID payoutId, String reference) {
        Payout payout = payoutRepository.findById(payoutId)
                .orElseThrow(() -> new NotFoundException("Payout not found"));
        if (payout.getStatus() == Payout.Status.PAID) {
            throw new InvalidStateException("This payout is already marked paid");
        }

        payout.markPaid(adminId, reference);
        payoutRepository.save(payout);
        return PayoutResponse.forAdmin(payout, experienceOf(payout), contributorOf(payout));
    }

    @Transactional(readOnly = true)
    public List<PayoutResponse> listMine(UUID contributorId) {
        List<Payout> payouts = payoutRepository.findByContributorIdOrderByCreatedAtDesc(contributorId);
        // Batch the experience lookup (one query) instead of a findById per row.
        Map<UUID, Experience> experiencesById = experiencesFor(payouts);
        return payouts.stream()
                .map(payout -> PayoutResponse.forContributor(payout, requireExperience(experiencesById, payout)))
                .toList();
    }

    private Map<UUID, Experience> experiencesFor(List<Payout> payouts) {
        List<UUID> ids = payouts.stream().map(Payout::getExperienceId).distinct().toList();
        return ids.isEmpty()
                ? Map.of()
                : experienceRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Experience::getId, e -> e));
    }

    private Map<UUID, User> contributorsFor(List<Payout> payouts) {
        List<UUID> ids = payouts.stream().map(Payout::getContributorId).distinct().toList();
        return ids.isEmpty()
                ? Map.of()
                : userRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));
    }

    private static Experience requireExperience(Map<UUID, Experience> experiencesById, Payout payout) {
        Experience experience = experiencesById.get(payout.getExperienceId());
        if (experience == null) {
            throw new NotFoundException("Experience not found");
        }
        return experience;
    }

    private static User requireContributor(Map<UUID, User> contributorsById, Payout payout) {
        User contributor = contributorsById.get(payout.getContributorId());
        if (contributor == null) {
            throw new NotFoundException("Contributor not found");
        }
        return contributor;
    }

    private Experience experienceOf(Payout payout) {
        return experienceRepository.findById(payout.getExperienceId())
                .orElseThrow(() -> new NotFoundException("Experience not found"));
    }

    private User contributorOf(Payout payout) {
        return userRepository.findById(payout.getContributorId())
                .orElseThrow(() -> new NotFoundException("Contributor not found"));
    }
}
