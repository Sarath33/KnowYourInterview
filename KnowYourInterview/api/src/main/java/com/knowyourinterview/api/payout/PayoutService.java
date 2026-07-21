package com.knowyourinterview.api.payout;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.experience.Payout;
import com.knowyourinterview.api.experience.PayoutRepository;
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
        return payoutRepository
                .findByStatusInOrderByCreatedAtAsc(List.of(Payout.Status.PENDING, Payout.Status.PROCESSING))
                .stream()
                .map(payout -> PayoutResponse.forAdmin(payout, experienceOf(payout), contributorOf(payout)))
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
        return payoutRepository.findByContributorIdOrderByCreatedAtDesc(contributorId).stream()
                .map(payout -> PayoutResponse.forContributor(payout, experienceOf(payout)))
                .toList();
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
