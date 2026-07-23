package com.knowyourinterview.api.experience;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.payment.EntitlementRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for AdminReviewService — repositories are mocked. Covers the
 * approve/reject status-machine guards and the side effects each transition triggers
 * (review log entry, payout ledger row on approve).
 */
@ExtendWith(MockitoExtension.class)
class AdminReviewServiceTest {

    private static final int CONTRIBUTOR_PAYOUT_PAISE = 50000;

    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private ExperienceRoundRepository roundRepository;
    @Mock
    private ProofDocumentRepository proofDocumentRepository;
    @Mock
    private ReviewLogRepository reviewLogRepository;
    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private EntitlementRepository entitlementRepository;

    private AdminReviewService service;

    @BeforeEach
    void setUp() {
        service = new AdminReviewService(
                experienceRepository, roundRepository, proofDocumentRepository,
                reviewLogRepository, payoutRepository, entitlementRepository, CONTRIBUTOR_PAYOUT_PAISE);
    }

    private Experience pendingReviewExperience() {
        Experience experience = new Experience(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", 19900);
        experience.markPendingReview();
        return experience;
    }

    @Test
    void reviewQueueReturnsOnlyPendingReviewExperiences() {
        Experience pending = pendingReviewExperience();
        when(experienceRepository.findByStatusOrderByCreatedAtAsc(ExperienceStatus.PENDING_REVIEW))
                .thenReturn(List.of(pending));

        List<ExperienceFullResponse> queue = service.reviewQueue();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).status()).isEqualTo(ExperienceStatus.PENDING_REVIEW);
    }

    @Test
    void approvePublishesExperienceLogsReviewAndCreatesPendingPayout() {
        Experience experience = pendingReviewExperience();
        UUID adminId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.approve(adminId, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.PUBLISHED);
        assertThat(experience.getPublishedAt()).isNotNull();

        ArgumentCaptor<ReviewLog> logCaptor = ArgumentCaptor.forClass(ReviewLog.class);
        verify(reviewLogRepository).save(logCaptor.capture());

        ArgumentCaptor<Payout> payoutCaptor = ArgumentCaptor.forClass(Payout.class);
        verify(payoutRepository).save(payoutCaptor.capture());
        assertThat(payoutCaptor.getValue().getAmountPaise()).isEqualTo(CONTRIBUTOR_PAYOUT_PAISE);
        assertThat(payoutCaptor.getValue().getContributorId()).isEqualTo(experience.getContributorId());
        assertThat(payoutCaptor.getValue().getStatus()).isEqualTo(Payout.Status.PENDING);
    }

    @Test
    void approveRejectsExperienceThatIsNotPendingReview() {
        Experience draft = new Experience(
                UUID.randomUUID(), UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", 19900);
        when(experienceRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), draft.getId()))
                .isInstanceOf(InvalidStateException.class);

        verify(payoutRepository, never()).save(any());
        verify(reviewLogRepository, never()).save(any());
    }

    @Test
    void approveRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), missingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void approveDoesNotCreateDuplicatePayoutIfCalledTwice() {
        Experience experience = pendingReviewExperience();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.approve(UUID.randomUUID(), experience.getId());
        // Second call: experience is now PUBLISHED, not PENDING_REVIEW, so it should be rejected
        // rather than silently creating a second payout row for the same experience.
        assertThatThrownBy(() -> service.approve(UUID.randomUUID(), experience.getId()))
                .isInstanceOf(InvalidStateException.class);

        verify(payoutRepository, times(1)).save(any());
    }

    @Test
    void rejectRecordsReasonAndTransitionsToRejected() {
        Experience experience = pendingReviewExperience();
        UUID adminId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.reject(adminId, experience.getId(), "Doesn't match our proof requirements");

        assertThat(response.status()).isEqualTo(ExperienceStatus.REJECTED);
        assertThat(response.rejectionReason()).isEqualTo("Doesn't match our proof requirements");
        verify(payoutRepository, never()).save(any());

        ArgumentCaptor<ReviewLog> logCaptor = ArgumentCaptor.forClass(ReviewLog.class);
        verify(reviewLogRepository).save(logCaptor.capture());
    }

    @Test
    void rejectRejectsExperienceThatIsNotPendingReview() {
        Experience published = pendingReviewExperience();
        published.publish();
        when(experienceRepository.findById(published.getId())).thenReturn(Optional.of(published));

        assertThatThrownBy(() -> service.reject(UUID.randomUUID(), published.getId(), "reason"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void getForReviewRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForReview(missingId))
                .isInstanceOf(NotFoundException.class);
    }
}
