package com.knowyourinterview.api.payout;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceOutcome;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.experience.Payout;
import com.knowyourinterview.api.experience.PayoutRepository;
import com.knowyourinterview.api.payout.dto.PayoutResponse;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for PayoutService (the manual-batch admin/contributor payout flow) —
 * repositories are mocked.
 */
@ExtendWith(MockitoExtension.class)
class PayoutServiceTest {

    @Mock
    private PayoutRepository payoutRepository;
    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private UserRepository userRepository;

    private PayoutService service;

    @BeforeEach
    void setUp() {
        service = new PayoutService(payoutRepository, experienceRepository, userRepository);
    }

    private Experience someExperience(UUID id) {
        return new Experience(
                id, UUID.randomUUID(), "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", 19900);
    }

    @Test
    void queueReturnsPendingAndProcessingPayoutsWithContributorAndExperienceDetails() {
        UUID experienceId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        Payout payout = new Payout(UUID.randomUUID(), experienceId, contributorId, 50000);
        Experience experience = someExperience(experienceId);
        User contributor = new User(contributorId, "jane@example.com", "hash", "Jane");

        when(payoutRepository.findByStatusInOrderByCreatedAtAsc(List.of(Payout.Status.PENDING, Payout.Status.PROCESSING)))
                .thenReturn(List.of(payout));
        when(experienceRepository.findById(experienceId)).thenReturn(Optional.of(experience));
        when(userRepository.findById(contributorId)).thenReturn(Optional.of(contributor));

        List<PayoutResponse> queue = service.queue();

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).contributorEmail()).isEqualTo("jane@example.com");
        assertThat(queue.get(0).company()).isEqualTo("Acme");
        assertThat(queue.get(0).amountPaise()).isEqualTo(50000);
    }

    @Test
    void queueSurfacesNotFoundIfExperienceWasDeletedOutFromUnderAPendingPayout() {
        UUID experienceId = UUID.randomUUID();
        Payout payout = new Payout(UUID.randomUUID(), experienceId, UUID.randomUUID(), 50000);
        when(payoutRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(payout));
        when(experienceRepository.findById(experienceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.queue()).isInstanceOf(NotFoundException.class);
    }

    @Test
    void markPaidRecordsAdminAndReferenceAndTransitionsStatus() {
        UUID experienceId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Payout payout = new Payout(UUID.randomUUID(), experienceId, contributorId, 50000);
        Experience experience = someExperience(experienceId);
        User contributor = new User(contributorId, "jane@example.com", "hash", "Jane");

        when(payoutRepository.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(experienceRepository.findById(experienceId)).thenReturn(Optional.of(experience));
        when(userRepository.findById(contributorId)).thenReturn(Optional.of(contributor));

        PayoutResponse response = service.markPaid(adminId, payout.getId(), "UPI-REF-123");

        assertThat(response.status()).isEqualTo(Payout.Status.PAID);
        assertThat(response.payoutReference()).isEqualTo("UPI-REF-123");
        assertThat(response.paidAt()).isNotNull();
        assertThat(payout.getPaidByAdminId()).isEqualTo(adminId);
        verify(payoutRepository).save(payout);
    }

    @Test
    void markPaidAllowsNullReference() {
        UUID experienceId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        Payout payout = new Payout(UUID.randomUUID(), experienceId, contributorId, 50000);
        when(payoutRepository.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(experienceRepository.findById(experienceId)).thenReturn(Optional.of(someExperience(experienceId)));
        when(userRepository.findById(contributorId)).thenReturn(Optional.of(new User(contributorId, "jane@example.com", "hash", "Jane")));

        PayoutResponse response = service.markPaid(UUID.randomUUID(), payout.getId(), null);

        assertThat(response.payoutReference()).isNull();
        assertThat(response.status()).isEqualTo(Payout.Status.PAID);
    }

    @Test
    void markPaidRejectsUnknownPayout() {
        UUID missingId = UUID.randomUUID();
        when(payoutRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPaid(UUID.randomUUID(), missingId, "ref"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void markPaidRejectsAlreadyPaidPayout() {
        Payout payout = new Payout(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 50000);
        payout.markPaid(UUID.randomUUID(), "old-ref");
        when(payoutRepository.findById(payout.getId())).thenReturn(Optional.of(payout));

        assertThatThrownBy(() -> service.markPaid(UUID.randomUUID(), payout.getId(), "new-ref"))
                .isInstanceOf(InvalidStateException.class);

        verify(payoutRepository, never()).save(any());
    }

    @Test
    void listMineReturnsOnlyThatContributorsPayoutsWithoutExposingOtherIdentityFields() {
        UUID contributorId = UUID.randomUUID();
        UUID experienceId = UUID.randomUUID();
        Payout payout = new Payout(UUID.randomUUID(), experienceId, contributorId, 50000);
        when(payoutRepository.findByContributorIdOrderByCreatedAtDesc(contributorId)).thenReturn(List.of(payout));
        when(experienceRepository.findById(experienceId)).thenReturn(Optional.of(someExperience(experienceId)));

        List<PayoutResponse> mine = service.listMine(contributorId);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).contributorEmail()).isNull();
        assertThat(mine.get(0).contributorDisplayName()).isNull();
    }
}
