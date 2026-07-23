package com.knowyourinterview.api.experience;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;

import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.payment.EntitlementRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for ExperienceService — repositories/storage are mocked. Covers
 * ownership checks, status-machine guards (draft-only mutation, submit-for-review
 * preconditions), and the teaser-vs-full-access branching in getPublicView.
 */
@ExtendWith(MockitoExtension.class)
class ExperienceServiceTest {

    private static final int DEFAULT_PRICE_PAISE = 19900;

    @Mock
    private ExperienceRepository experienceRepository;
    @Mock
    private ExperienceRoundRepository roundRepository;
    @Mock
    private ProofDocumentRepository proofDocumentRepository;
    @Mock
    private ProofStorageService proofStorageService;
    @Mock
    private EntitlementRepository entitlementRepository;
    @Mock
    private ReviewLogRepository reviewLogRepository;
    @Mock
    private PayoutRepository payoutRepository;

    private ExperienceService service;

    private final UUID contributorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ExperienceService(
                experienceRepository, roundRepository, proofDocumentRepository,
                proofStorageService, entitlementRepository, reviewLogRepository, payoutRepository,
                DEFAULT_PRICE_PAISE);
    }

    private ExperienceRequest sampleRequest() {
        return new ExperienceRequest(
                "Acme", "Backend Engineer", "L4", "Bengaluru", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Went well overall.",
                "Practice system design.", (short) 3, "3 weeks", "35 LPA");
    }

    private Experience draftOwnedByContributor() {
        return new Experience(
                UUID.randomUUID(), contributorId, "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", DEFAULT_PRICE_PAISE);
    }

    // --- createDraft ---

    @Test
    void createDraftSavesNewExperienceAtDefaultPriceAndDraftStatus() {
        ExperienceFullResponse response = service.createDraft(contributorId, sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        assertThat(response.status()).isEqualTo(ExperienceStatus.DRAFT);
        assertThat(response.pricePaise()).isEqualTo(DEFAULT_PRICE_PAISE);
        // A brand new draft has no rounds yet — roundCount should reflect that, not be null/unset.
        assertThat(response.roundCount()).isZero();
        // Nobody's unlocked a draft that was never published.
        assertThat(response.unlockCount()).isZero();
        verify(experienceRepository).save(any(Experience.class));
    }

    // --- updateDraft ---

    @Test
    void updateDraftAppliesEditsWhenOwnedAndStillDraft() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceRequest updated = new ExperienceRequest(
                "New Co", "Staff Engineer", "L5", "Remote", true,
                (short) 7, (short) 2026, ExperienceOutcome.OFFER, "new teaser",
                "new advice", (short) 4, "2 weeks", "45 LPA");
        ExperienceFullResponse response = service.updateDraft(contributorId, experience.getId(), updated);

        assertThat(response.company()).isEqualTo("New Co");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.updateDraft(UUID.randomUUID(), experience.getId(), sampleRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateDraftRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDraft(contributorId, missingId, sampleRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDraftAllowsEditingAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.updateDraft(contributorId, experience.getId(), sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.updateDraft(contributorId, experience.getId(), sampleRequest()))
                .isInstanceOf(InvalidStateException.class);
    }

    // --- addRound / deleteRound ---

    @Test
    void addRoundNumbersRoundsSequentially() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(2L);

        RoundRequest req = new RoundRequest("ONSITE", (short) 60, "Reverse a tree", List.of("trees", "recursion"), "Solved it", "Friendly", (short) 3);
        ExperienceRoundResponse response = service.addRound(contributorId, experience.getId(), req);

        assertThat(response.roundNumber()).isEqualTo((short) 3);
        assertThat(response.roundType()).isEqualTo("ONSITE");
    }

    @Test
    void addRoundAllowsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(0L);

        RoundRequest req = new RoundRequest("ONSITE", null, null, null, null, null, null);
        ExperienceRoundResponse response = service.addRound(contributorId, experience.getId(), req);

        assertThat(response.roundType()).isEqualTo("ONSITE");
    }

    @Test
    void addRoundRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        RoundRequest req = new RoundRequest("ONSITE", null, null, null, null, null, null);
        assertThatThrownBy(() -> service.addRound(contributorId, experience.getId(), req))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void deleteRoundRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteRound(UUID.randomUUID(), experience.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
        verify(roundRepository, never()).deleteByIdAndExperienceId(any(), any());
    }

    @Test
    void deleteRoundAllowsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.deleteRound(contributorId, experience.getId(), UUID.randomUUID());

        verify(roundRepository).deleteByIdAndExperienceId(any(), eq(experience.getId()));
    }

    // --- updateRound ---

    @Test
    void updateRoundEditsFieldsInPlaceWithoutTouchingRoundNumber() {
        Experience experience = draftOwnedByContributor();
        ExperienceRound round = new ExperienceRound(
                UUID.randomUUID(), experience.getId(), (short) 2, "ONSITE", (short) 45, "old question",
                "old,tags", "old approach", "old interviewer", (short) 2);
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.findByIdAndExperienceId(round.getId(), experience.getId())).thenReturn(Optional.of(round));

        RoundRequest req = new RoundRequest(
                "SYSTEM_DESIGN", (short) 60, "new question", List.of("new", "tags"), "new approach",
                "new interviewer", (short) 4);
        ExperienceRoundResponse response = service.updateRound(contributorId, experience.getId(), round.getId(), req);

        assertThat(response.roundNumber()).isEqualTo((short) 2);
        assertThat(response.roundType()).isEqualTo("SYSTEM_DESIGN");
        assertThat(response.questionsAsked()).isEqualTo("new question");
        assertThat(response.topicsTags()).containsExactly("new", "tags");
        assertThat(response.difficulty()).isEqualTo((short) 4);
        verify(roundRepository).save(round);
    }

    @Test
    void updateRoundRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        RoundRequest req = new RoundRequest("ONSITE", null, null, null, null, null, null);
        assertThatThrownBy(() -> service.updateRound(UUID.randomUUID(), experience.getId(), UUID.randomUUID(), req))
                .isInstanceOf(ForbiddenException.class);
        verify(roundRepository, never()).save(any());
    }

    @Test
    void updateRoundRejectsUnknownRound() {
        Experience experience = draftOwnedByContributor();
        UUID roundId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.findByIdAndExperienceId(roundId, experience.getId())).thenReturn(Optional.empty());

        RoundRequest req = new RoundRequest("ONSITE", null, null, null, null, null, null);
        assertThatThrownBy(() -> service.updateRound(contributorId, experience.getId(), roundId, req))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRoundAllowsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        ExperienceRound round = new ExperienceRound(
                UUID.randomUUID(), experience.getId(), (short) 1, "ONSITE", null, null, null, null, null, null);
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.findByIdAndExperienceId(round.getId(), experience.getId())).thenReturn(Optional.of(round));

        RoundRequest req = new RoundRequest("CODING", null, null, null, null, null, null);
        ExperienceRoundResponse response = service.updateRound(contributorId, experience.getId(), round.getId(), req);

        assertThat(response.roundType()).isEqualTo("CODING");
    }

    @Test
    void updateRoundRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        RoundRequest req = new RoundRequest("ONSITE", null, null, null, null, null, null);
        assertThatThrownBy(() -> service.updateRound(contributorId, experience.getId(), UUID.randomUUID(), req))
                .isInstanceOf(InvalidStateException.class);
        verify(roundRepository, never()).findByIdAndExperienceId(any(), any());
    }

    // --- uploadProof ---

    @Test
    void uploadProofStoresFileAndSavesRecordWhenDraft() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofStorageService.store(eq(experience.getId()), eq("offer.pdf"), any(InputStream.class)))
                .thenReturn(new ProofStorageService.StoredFile("some/key.pdf", 1024L));

        MockMultipartFile file = new MockMultipartFile("file", "offer.pdf", "application/pdf", "content".getBytes());
        var response = service.uploadProof(contributorId, experience.getId(), file);

        assertThat(response.fileName()).isEqualTo("offer.pdf");
        verify(proofDocumentRepository).save(any(ProofDocument.class));
    }

    @Test
    void uploadProofRejectsEmptyFile() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        MockMultipartFile empty = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);
        assertThatThrownBy(() -> service.uploadProof(contributorId, experience.getId(), empty))
                .isInstanceOf(InvalidStateException.class);
        verify(proofDocumentRepository, never()).save(any());
    }

    @Test
    void uploadProofAllowsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofStorageService.store(eq(experience.getId()), eq("offer.pdf"), any(InputStream.class)))
                .thenReturn(new ProofStorageService.StoredFile("some/key.pdf", 1024L));

        MockMultipartFile file = new MockMultipartFile("file", "offer.pdf", "application/pdf", "content".getBytes());
        var response = service.uploadProof(contributorId, experience.getId(), file);

        assertThat(response.fileName()).isEqualTo("offer.pdf");
    }

    @Test
    void uploadProofRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        MockMultipartFile file = new MockMultipartFile("file", "offer.pdf", "application/pdf", "content".getBytes());
        assertThatThrownBy(() -> service.uploadProof(contributorId, experience.getId(), file))
                .isInstanceOf(InvalidStateException.class);
    }

    // --- submitForReview ---

    @Test
    void submitForReviewRequiresAtLeastOneRound() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.submitForReview(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("round");
    }

    @Test
    void submitForReviewRequiresAtLeastOneProofDocument() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(1L);
        when(proofDocumentRepository.countByExperienceId(experience.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.submitForReview(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("proof");
    }

    @Test
    void submitForReviewMovesDraftToPendingReviewWhenRequirementsMet() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(1L);
        when(proofDocumentRepository.countByExperienceId(experience.getId())).thenReturn(1L);

        ExperienceFullResponse response = service.submitForReview(contributorId, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.PENDING_REVIEW);
    }

    // --- getPublicView ---

    @Test
    void getPublicViewGivesFullAccessToOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceViewResponse response = service.getPublicView(contributorId, false, experience.getId());

        assertThat(response.entitled()).isTrue();
    }

    @Test
    void getPublicViewGivesFullAccessToAdminRegardlessOfStatus() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceViewResponse response = service.getPublicView(UUID.randomUUID(), true, experience.getId());

        assertThat(response.entitled()).isTrue();
    }

    @Test
    void getPublicViewHidesUnpublishedExperienceFromStrangers() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.getPublicView(UUID.randomUUID(), false, experience.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getPublicViewGivesTeaserToViewerWithoutEntitlement() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        UUID viewerId = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(viewerId, experience.getId())).thenReturn(false);

        ExperienceViewResponse response = service.getPublicView(viewerId, false, experience.getId());

        assertThat(response.entitled()).isFalse();
    }

    @Test
    void getPublicViewGivesFullAccessToEntitledViewer() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        UUID viewerId = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(viewerId, experience.getId())).thenReturn(true);

        ExperienceViewResponse response = service.getPublicView(viewerId, false, experience.getId());

        assertThat(response.entitled()).isTrue();
    }

    @Test
    void getPublicViewAllowsAnonymousViewerToSeeTeaserOfPublished() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceViewResponse response = service.getPublicView(null, false, experience.getId());

        assertThat(response.entitled()).isFalse();
    }

    @Test
    void getPublicViewTeaserIncludesRoundCount() {
        // Round count rides along on the teaser so a viewer can gauge content depth
        // before paying, without the round content itself leaking.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(2L);

        ExperienceViewResponse response = service.getPublicView(null, false, experience.getId());

        assertThat(response.teaser().roundCount()).isEqualTo(2);
    }

    // --- downloadProof ---

    @Test
    void downloadProofRejectsViewerWhoIsNeitherOwnerNorAdmin() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.downloadProof(UUID.randomUUID(), false, experience.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void downloadProofReturnsContentForOwner() {
        Experience experience = draftOwnedByContributor();
        ProofDocument doc = new ProofDocument(UUID.randomUUID(), experience.getId(), "key.pdf", "offer.pdf", "application/pdf");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(doc.getId(), experience.getId())).thenReturn(Optional.of(doc));
        InputStream fakeStream = new ByteArrayInputStream("pdf-bytes".getBytes());
        when(proofStorageService.retrieve("key.pdf")).thenReturn(fakeStream);

        ExperienceService.ProofDownload download = service.downloadProof(contributorId, false, experience.getId(), doc.getId());

        assertThat(download.document()).isEqualTo(doc);
        assertThat(download.content()).isSameAs(fakeStream);
    }

    @Test
    void downloadProofRejectsUnknownDocument() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(any(), eq(experience.getId()))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadProof(contributorId, false, experience.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- browsePublished ---

    private static final Sort NEWEST_SORT = Sort.by(Sort.Direction.DESC, "publishedAt");

    @Test
    void browsePublishedMapsRepositoryPageToTeasers() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        Page<Experience> page = new PageImpl<>(List.of(experience), PageRequest.of(0, 20, NEWEST_SORT), 1);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);

        var response = service.browsePublished(null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(1);
    }

    @Test
    void browsePublishedIncludesRoundCountPerExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        Page<Experience> page = new PageImpl<>(List.of(experience), PageRequest.of(0, 20, NEWEST_SORT), 1);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);
        ExperienceRoundRepository.ExperienceRoundCount count = mock(ExperienceRoundRepository.ExperienceRoundCount.class);
        when(count.getExperienceId()).thenReturn(experience.getId());
        when(count.getRoundCount()).thenReturn(3L);
        when(roundRepository.countByExperienceIdIn(List.of(experience.getId()))).thenReturn(List.of(count));

        var response = service.browsePublished(null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).roundCount()).isEqualTo(3);
    }

    @Test
    void browsePublishedDefaultsRoundCountToZeroForExperienceWithNoRounds() {
        // An experience with zero rounds simply doesn't appear in the bulk count query's
        // result — the service should default it to 0, not throw or leave it null.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        Page<Experience> page = new PageImpl<>(List.of(experience), PageRequest.of(0, 20, NEWEST_SORT), 1);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);
        when(roundRepository.countByExperienceIdIn(List.of(experience.getId()))).thenReturn(List.of());

        var response = service.browsePublished(null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).roundCount()).isZero();
    }

    @Test
    void browsePublishedSkipsRoundCountQueryForAnEmptyPage() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);

        var response = service.browsePublished(null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items()).isEmpty();
        verify(roundRepository, never()).countByExperienceIdIn(any());
    }

    @Test
    void browsePublishedCapsPageSizeAtOneHundred() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 100, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 100, NEWEST_SORT))))
                .thenReturn(page);

        service.browsePublished(null, null, null, null, null, null, "newest", 0, 500);

        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(PageRequest.of(0, 100, NEWEST_SORT)));
    }

    @Test
    void browsePublishedTreatsBlankFiltersAsNull() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(eq(null), eq(null), eq(null), eq(null), eq(null), any()))
                .thenReturn(page);

        service.browsePublished(null, "  ", "", null, null, "  ", "newest", 0, 20);

        verify(experienceRepository).browsePublished(eq(null), eq(null), eq(null), eq(null), eq(null), any());
    }

    @Test
    void browsePublishedBuildsALowercasedWildcardSearchPattern() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), any(), any())).thenReturn(page);

        service.browsePublished(null, null, null, null, null, "Backend Eng", "newest", 0, 20);

        verify(experienceRepository).browsePublished(eq(null), eq(null), eq(null), eq(null), eq("%backend eng%"), any());
    }

    @Test
    void browsePublishedSortsByPriceLowWhenRequested() {
        Sort priceLowSort = Sort.by(Sort.Direction.ASC, "pricePaise");
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, priceLowSort), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, priceLowSort))))
                .thenReturn(page);

        service.browsePublished(null, null, null, null, null, null, "priceLow", 0, 20);

        verify(experienceRepository).browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, priceLowSort)));
    }

    @Test
    void browsePublishedSortsByPriceHighWhenRequested() {
        Sort priceHighSort = Sort.by(Sort.Direction.DESC, "pricePaise");
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, priceHighSort), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, priceHighSort))))
                .thenReturn(page);

        service.browsePublished(null, null, null, null, null, null, "priceHigh", 0, 20);

        verify(experienceRepository).browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, priceHighSort)));
    }

    @Test
    void browsePublishedFallsBackToNewestForAnUnrecognizedSortValue() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, NEWEST_SORT))))
                .thenReturn(page);

        service.browsePublished(null, null, null, null, null, null, "bogus", 0, 20);

        verify(experienceRepository).browsePublished(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 20, NEWEST_SORT)));
    }

    @Test
    void browsePublishedMarksEverythingUnlockedFalseForAGuest() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        Page<Experience> page = new PageImpl<>(List.of(experience), PageRequest.of(0, 20, NEWEST_SORT), 1);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);

        var response = service.browsePublished(null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).unlocked()).isFalse();
        verify(entitlementRepository, never()).findExperienceIdsByUserIdAndExperienceIdIn(any(), any());
    }

    @Test
    void browsePublishedMarksOnlyEntitledExperiencesAsUnlockedForASignedInViewer() {
        Experience unlocked = draftOwnedByContributor();
        unlocked.markPendingReview();
        unlocked.publish();
        Experience locked = draftOwnedByContributor();
        locked.markPendingReview();
        locked.publish();
        UUID viewerId = UUID.randomUUID();
        Page<Experience> page = new PageImpl<>(List.of(unlocked, locked), PageRequest.of(0, 20, NEWEST_SORT), 2);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);
        when(entitlementRepository.findExperienceIdsByUserIdAndExperienceIdIn(
                        eq(viewerId), eq(List.of(unlocked.getId(), locked.getId()))))
                .thenReturn(List.of(unlocked.getId()));

        var response = service.browsePublished(viewerId, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).unlocked()).isTrue();
        assertThat(response.items().get(1).unlocked()).isFalse();
    }

    @Test
    void browsePublishedSkipsEntitlementQueryForAnEmptyPage() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20, NEWEST_SORT), 0);
        when(experienceRepository.browsePublished(null, null, null, null, null, PageRequest.of(0, 20, NEWEST_SORT)))
                .thenReturn(page);

        service.browsePublished(UUID.randomUUID(), null, null, null, null, null, "newest", 0, 20);

        verify(entitlementRepository, never()).findExperienceIdsByUserIdAndExperienceIdIn(any(), any());
    }

    // --- listMine / getMine ---

    @Test
    void listMineReturnsContributorsExperiencesOnly() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findByContributorIdOrderByCreatedAtDesc(contributorId)).thenReturn(List.of(experience));

        List<ExperienceFullResponse> mine = service.listMine(contributorId);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).company()).isEqualTo("Acme");
    }

    @Test
    void getMineRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.getMine(UUID.randomUUID(), experience.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getMineIncludesHowManyPeopleHaveUnlockedIt() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(entitlementRepository.countByExperienceId(experience.getId())).thenReturn(12L);

        ExperienceFullResponse response = service.getMine(contributorId, experience.getId());

        assertThat(response.unlockCount()).isEqualTo(12L);
    }

    // --- resubmission after rejection ---

    private Experience rejectedOwnedByContributor() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.reject("Missing detail on the system design round");
        return experience;
    }

    @Test
    void updateDraftAllowsEditingARejectedExperience() {
        Experience experience = rejectedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.updateDraft(contributorId, experience.getId(), sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        verify(experienceRepository).save(experience);
    }

    @Test
    void addRoundAllowsARejectedExperience() {
        Experience experience = rejectedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(0L);

        RoundRequest req = new RoundRequest("ONSITE", null, "More detail this time", null, null, null, null);
        ExperienceRoundResponse response = service.addRound(contributorId, experience.getId(), req);

        assertThat(response.roundNumber()).isEqualTo((short) 1);
    }

    @Test
    void submitForReviewRejectsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.submitForReview(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void submitForReviewResubmitsARejectedExperienceAndClearsTheRejectionReason() {
        Experience experience = rejectedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(1L);
        when(proofDocumentRepository.countByExperienceId(experience.getId())).thenReturn(1L);

        ExperienceFullResponse response = service.submitForReview(contributorId, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.PENDING_REVIEW);
        assertThat(response.rejectionReason()).isNull();
    }

    // --- deleteProof ---

    @Test
    void deleteProofRemovesDbRowAndStoredFileWhenEditable() {
        Experience experience = draftOwnedByContributor();
        ProofDocument doc = new ProofDocument(UUID.randomUUID(), experience.getId(), "key.pdf", "offer.pdf", "application/pdf");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(doc.getId(), experience.getId())).thenReturn(Optional.of(doc));

        service.deleteProof(contributorId, experience.getId(), doc.getId());

        verify(proofDocumentRepository).delete(doc);
        verify(proofStorageService).delete("key.pdf");
    }

    @Test
    void deleteProofWorksOnARejectedExperienceToo() {
        Experience experience = rejectedOwnedByContributor();
        ProofDocument doc = new ProofDocument(UUID.randomUUID(), experience.getId(), "key.pdf", "offer.pdf", "application/pdf");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(doc.getId(), experience.getId())).thenReturn(Optional.of(doc));

        service.deleteProof(contributorId, experience.getId(), doc.getId());

        verify(proofStorageService).delete("key.pdf");
    }

    @Test
    void deleteProofAllowsAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        ProofDocument doc = new ProofDocument(UUID.randomUUID(), experience.getId(), "key.pdf", "offer.pdf", "application/pdf");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(doc.getId(), experience.getId())).thenReturn(Optional.of(doc));

        service.deleteProof(contributorId, experience.getId(), doc.getId());

        verify(proofStorageService).delete("key.pdf");
    }

    @Test
    void deleteProofRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteProof(contributorId, experience.getId(), UUID.randomUUID()))
                .isInstanceOf(InvalidStateException.class);
        verify(proofStorageService, never()).delete(any());
    }

    @Test
    void deleteProofRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteProof(UUID.randomUUID(), experience.getId(), UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deleteProofRejectsUnknownDocument() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByIdAndExperienceId(any(), eq(experience.getId()))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProof(contributorId, experience.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- deleteExperience ---

    @Test
    void deleteExperienceCascadesToProofFilesAndRounds() {
        Experience experience = draftOwnedByContributor();
        ProofDocument doc1 = new ProofDocument(UUID.randomUUID(), experience.getId(), "key1.pdf", "a.pdf", "application/pdf");
        ProofDocument doc2 = new ProofDocument(UUID.randomUUID(), experience.getId(), "key2.pdf", "b.pdf", "application/pdf");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByExperienceId(experience.getId())).thenReturn(List.of(doc1, doc2));

        service.deleteExperience(contributorId, experience.getId());

        verify(proofStorageService).delete("key1.pdf");
        verify(proofStorageService).delete("key2.pdf");
        verify(proofDocumentRepository).deleteAll(List.of(doc1, doc2));
        verify(roundRepository).deleteByExperienceId(experience.getId());
        verify(reviewLogRepository).deleteByExperienceId(experience.getId());
        verify(experienceRepository).delete(experience);
    }

    @Test
    void deleteExperienceWorksOnARejectedExperienceToo() {
        // Regression test: a rejected experience always has at least one review_logs row
        // (AdminReviewService.reject() writes one), and that FK isn't ON DELETE CASCADE —
        // without the explicit reviewLogRepository.deleteByExperienceId() call in
        // deleteExperience(), this would fail with a raw foreign-key violation instead of
        // actually deleting anything.
        Experience experience = rejectedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByExperienceId(experience.getId())).thenReturn(List.of());

        service.deleteExperience(contributorId, experience.getId());

        verify(reviewLogRepository).deleteByExperienceId(experience.getId());
        verify(experienceRepository).delete(experience);
    }

    @Test
    void deleteExperienceRejectsAnExperienceThatHasBeenPurchased() {
        // A DRAFT here doesn't necessarily mean "never published" — unpublish() can put a
        // formerly-PUBLISHED, formerly-purchased experience back into DRAFT. Deleting that
        // would break an existing purchaser's access, so an entitlement on record blocks it.
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(entitlementRepository.existsByExperienceId(experience.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteExperience(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("purchased");

        verify(experienceRepository, never()).delete(any());
        verify(proofDocumentRepository, never()).deleteAll(any());
    }

    @Test
    void deleteExperienceRejectsAnExperienceWithAPayoutOnRecord() {
        // Same scenario as above but for a payout created at approval time (money owed
        // to the contributor) rather than a purchase — also shouldn't silently disappear.
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(entitlementRepository.existsByExperienceId(experience.getId())).thenReturn(false);
        when(payoutRepository.existsByExperienceId(experience.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteExperience(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("payout");

        verify(experienceRepository, never()).delete(any());
    }

    @Test
    void deleteExperienceRejectsAPendingReviewExperience() {
        // Deliberate asymmetry vs. content edits: PENDING_REVIEW is now editable, but
        // withdrawing a submission outright while an admin may be actively reviewing it
        // is a bigger action than a content edit — deleteExperience stays DRAFT/REJECTED
        // only. See requireDraftOrRejected's Javadoc.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteExperience(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class);
        verify(experienceRepository, never()).delete(any());
    }

    @Test
    void deleteExperienceRejectsWhenNotEditable() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteExperience(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class);
        verify(experienceRepository, never()).delete(any());
    }

    @Test
    void deleteExperienceRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.deleteExperience(UUID.randomUUID(), experience.getId()))
                .isInstanceOf(ForbiddenException.class);
        verify(experienceRepository, never()).delete(any());
    }

    // --- unpublish ---

    private Experience publishedOwnedByContributor() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        return experience;
    }

    @Test
    void unpublishRevertsOwnersExperienceToDraft() {
        Experience experience = publishedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.unpublish(contributorId, false, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.DRAFT);
        assertThat(response.publishedAt()).isNull();
    }

    @Test
    void unpublishAllowsAnAdminRegardlessOfOwnership() {
        Experience experience = publishedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.unpublish(UUID.randomUUID(), true, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.DRAFT);
    }

    @Test
    void unpublishRejectsNonOwnerNonAdmin() {
        Experience experience = publishedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.unpublish(UUID.randomUUID(), false, experience.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void unpublishRejectsAnExperienceThatIsNotPublished() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.unpublish(contributorId, false, experience.getId()))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void unpublishRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unpublish(contributorId, false, missingId))
                .isInstanceOf(NotFoundException.class);
    }

    // --- getPublicView: entitlement survives an unpublish ---

    @Test
    void getPublicViewKeepsFullAccessForAPurchaserAfterTheExperienceIsUnpublished() {
        // Regression test for a real bug found while building unpublish(): the visibility
        // gate used to run before the entitlement check, so a paying viewer got a 404 the
        // instant status wasn't PUBLISHED. This confirms entitlement now grants access on
        // its own, independent of status — an unpublish-for-edit doesn't lock out people
        // who already paid.
        Experience experience = publishedOwnedByContributor();
        experience.unpublish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        UUID purchaserId = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(purchaserId, experience.getId())).thenReturn(true);

        ExperienceViewResponse response = service.getPublicView(purchaserId, false, experience.getId());

        assertThat(response.entitled()).isTrue();
    }

    @Test
    void getPublicViewHidesAnUnpublishedExperienceFromANonPurchasingStranger() {
        Experience experience = publishedOwnedByContributor();
        experience.unpublish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        UUID strangerId = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(strangerId, experience.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.getPublicView(strangerId, false, experience.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}
