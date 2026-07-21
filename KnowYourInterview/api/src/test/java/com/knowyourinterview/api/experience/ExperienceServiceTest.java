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

    private ExperienceService service;

    private final UUID contributorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ExperienceService(
                experienceRepository, roundRepository, proofDocumentRepository,
                proofStorageService, entitlementRepository, DEFAULT_PRICE_PAISE);
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
    void updateDraftRejectsExperienceThatIsNoLongerADraft() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
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
    void addRoundRejectsWhenExperienceIsNotADraft() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
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
    void uploadProofRejectsWhenExperienceIsNotADraft() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
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

    @Test
    void browsePublishedMapsRepositoryPageToTeasers() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        Page<Experience> page = new PageImpl<>(List.of(experience), PageRequest.of(0, 20), 1);
        when(experienceRepository.browsePublished(null, null, null, null, PageRequest.of(0, 20))).thenReturn(page);

        var response = service.browsePublished(null, null, null, null, 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(1);
    }

    @Test
    void browsePublishedCapsPageSizeAtOneHundred() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
        when(experienceRepository.browsePublished(any(), any(), any(), any(), eq(PageRequest.of(0, 100)))).thenReturn(page);

        service.browsePublished(null, null, null, null, 0, 500);

        verify(experienceRepository).browsePublished(eq(null), eq(null), eq(null), eq(null), eq(PageRequest.of(0, 100)));
    }

    @Test
    void browsePublishedTreatsBlankFiltersAsNull() {
        Page<Experience> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        when(experienceRepository.browsePublished(eq(null), eq(null), eq(null), eq(null), any())).thenReturn(page);

        service.browsePublished("  ", "", null, null, 0, 20);

        verify(experienceRepository).browsePublished(eq(null), eq(null), eq(null), eq(null), any());
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
    void deleteProofRejectsWhenExperienceIsNotEditable() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
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
        verify(experienceRepository).delete(experience);
    }

    @Test
    void deleteExperienceWorksOnARejectedExperienceToo() {
        Experience experience = rejectedOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByExperienceId(experience.getId())).thenReturn(List.of());

        service.deleteExperience(contributorId, experience.getId());

        verify(experienceRepository).delete(experience);
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
