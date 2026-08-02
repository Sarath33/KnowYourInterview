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

import com.knowyourinterview.api.auth.EmailNotVerifiedException;
import com.knowyourinterview.api.auth.EmailVerificationGuard;
import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.dto.ExperienceEditSnapshotResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.payment.EntitlementRepository;
import com.knowyourinterview.api.payout.PayoutRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private static final int MAX_PAGE_SIZE = 100;
    private static final double SIMILARITY_THRESHOLD = 0.3;
    private static final double SUGGESTION_THRESHOLD = 0.15;

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
    @Mock
    private ExperienceResponseAssembler responseAssembler;
    @Mock
    private ExperienceEditSnapshotRepository editSnapshotRepository;
    @Mock
    private ExperienceViewRepository experienceViewRepository;
    /** A mock does nothing by default, so every test here implicitly runs as a confirmed
     * user. The tests that care about the gate stub it to throw instead. */
    @Mock
    private EmailVerificationGuard emailVerificationGuard;

    private ExperienceService service;

    private final UUID contributorId = UUID.randomUUID();

    /** Mirrors ExperienceResponseAssembler's real toFullResponse/buildMany against this
     * test's own repo mocks, so every existing assertion on the built response (roundCount,
     * unlockCount, etc.) keeps working exactly as it did before that logic was extracted
     * into its own class — only ExperienceService's own behavior is under test here, not
     * the assembler's (that has its own test coverage). lenient() because plenty of tests
     * below never reach a response-building call at all (they throw first). */
    @BeforeEach
    void setUp() {
        service = new ExperienceService(
                experienceRepository, roundRepository, proofDocumentRepository,
                proofStorageService, entitlementRepository, reviewLogRepository, payoutRepository,
                responseAssembler, editSnapshotRepository, experienceViewRepository,
                emailVerificationGuard, DEFAULT_PRICE_PAISE, MAX_PAGE_SIZE, SIMILARITY_THRESHOLD,
                SUGGESTION_THRESHOLD);

        // Default: every recordView call looks like a genuinely new view (return 1, as a
        // real INSERT ... ON CONFLICT DO NOTHING would for a viewer's first visit) — tests
        // specifically about the one-per-user dedup override this to return 0 to simulate
        // a repeat view.
        lenient().when(experienceViewRepository.recordView(any(), any(), any())).thenReturn(1);

        lenient().when(responseAssembler.toFullResponse(any())).thenAnswer(inv -> {
            Experience e = inv.getArgument(0);
            return ExperienceFullResponse.from(e, roundResponsesFor(e), proofResponsesFor(e),
                    entitlementRepository.countByExperienceId(e.getId()));
        });
        // getPublicView calls the 2-arg overload (to control confidentialNote visibility)
        // instead of the 1-arg one every other caller uses — needs its own stub or it'd
        // fall through to Mockito's default null return.
        lenient().when(responseAssembler.toFullResponse(any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> {
                    Experience e = inv.getArgument(0);
                    boolean includeConfidentialNote = inv.getArgument(1);
                    return ExperienceFullResponse.from(e, roundResponsesFor(e), proofResponsesFor(e),
                            entitlementRepository.countByExperienceId(e.getId()), includeConfidentialNote);
                });
        lenient().when(responseAssembler.buildMany(any())).thenAnswer(inv -> {
            List<Experience> experiences = inv.getArgument(0);
            return experiences.stream()
                    .map(e -> ExperienceFullResponse.from(e, roundResponsesFor(e), proofResponsesFor(e),
                            entitlementRepository.countByExperienceId(e.getId())))
                    .toList();
        });
    }

    private List<ExperienceRoundResponse> roundResponsesFor(Experience e) {
        return roundRepository.findByExperienceIdOrderByRoundNumberAsc(e.getId()).stream()
                .map(ExperienceRoundResponse::from)
                .toList();
    }

    private List<ProofDocumentResponse> proofResponsesFor(Experience e) {
        return proofDocumentRepository.findByExperienceId(e.getId()).stream()
                .map(ProofDocumentResponse::from)
                .toList();
    }

    private ExperienceRequest sampleRequest() {
        return new ExperienceRequest(
                "Acme", "Backend Engineer", "L4", "Bengaluru", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Went well overall.",
                "Practice system design.", (short) 3, "3 weeks", "35 LPA", null, null, false, null);
    }

    private ExperienceRequest referenceRequest() {
        return new ExperienceRequest(
                "Acme", "Backend Engineer", "L4", "Bengaluru", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Went well overall.",
                "Practice system design.", (short) 3, "3 weeks", "35 LPA",
                "https://example.com/interview-writeup", "Example Blog", false, null);
    }

    private ExperienceRequest freeContributionRequest() {
        return new ExperienceRequest(
                "Acme", "Backend Engineer", "L4", "Bengaluru", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "Went well overall.",
                "Practice system design.", (short) 3, "3 weeks", "35 LPA", null, null, true, null);
    }

    private Experience draftOwnedByContributor() {
        return new Experience(
                UUID.randomUUID(), contributorId, "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", null, DEFAULT_PRICE_PAISE);
    }

    private Experience freeContributionDraftOwnedByContributor() {
        Experience experience = new Experience(
                UUID.randomUUID(), contributorId, "Acme", "Backend Engineer", "L4", "Bengaluru",
                true, (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser", "advice",
                (short) 3, "3 weeks", "35 LPA", null, 0);
        experience.markAsFreeContribution();
        return experience;
    }

    // --- createDraft ---

    @Test
    void createDraftSavesNewExperienceAtDefaultPriceAndDraftStatus() {
        ExperienceFullResponse response = service.createDraft(contributorId, false, sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        assertThat(response.status()).isEqualTo(ExperienceStatus.DRAFT);
        assertThat(response.pricePaise()).isEqualTo(DEFAULT_PRICE_PAISE);
        assertThat(response.isFree()).isFalse();
        // A brand new draft has no rounds yet — roundCount should reflect that, not be null/unset.
        assertThat(response.roundCount()).isZero();
        // Nobody's unlocked a draft that was never published.
        assertThat(response.unlockCount()).isZero();
        verify(experienceRepository).save(any(Experience.class));
    }

    @Test
    void createDraftRejectsSourceReferenceFromNonAdmin() {
        assertThatThrownBy(() -> service.createDraft(contributorId, false, referenceRequest()))
                .isInstanceOf(ForbiddenException.class);
        verify(experienceRepository, never()).save(any());
    }

    @Test
    void createDraftAllowsSourceReferenceFromAdminAndForcesFree() {
        ExperienceFullResponse response = service.createDraft(contributorId, true, referenceRequest());

        assertThat(response.isFree()).isTrue();
        assertThat(response.pricePaise()).isZero();
        assertThat(response.sourceUrl()).isEqualTo("https://example.com/interview-writeup");
        assertThat(response.sourceName()).isEqualTo("Example Blog");
        verify(experienceRepository).save(any(Experience.class));
    }

    @Test
    void createDraftAllowsFreeContributionFromAnyNonAdminContributor() {
        ExperienceFullResponse response = service.createDraft(contributorId, false, freeContributionRequest());

        assertThat(response.isFree()).isTrue();
        assertThat(response.pricePaise()).isZero();
        assertThat(response.sourceUrl()).isNull();
        assertThat(response.sourceName()).isNull();
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
                "new advice", (short) 4, "2 weeks", "45 LPA", null, null, false, null);
        ExperienceFullResponse response = service.updateDraft(contributorId, false, experience.getId(), updated);

        assertThat(response.company()).isEqualTo("New Co");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftRejectsNonOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.updateDraft(UUID.randomUUID(), false, experience.getId(), sampleRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateDraftAllowsAdminToEditAnExperienceTheyDontOwn() {
        // Part of the correction-requested flow: an admin can fix a submission's fields
        // directly, not just leave a note. Applied immediately, same as a contributor's
        // own edit — see AdminReviewService#requestCorrection.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response =
                service.updateDraft(UUID.randomUUID(), true, experience.getId(), sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDraft(contributorId, false, missingId, sampleRequest()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateDraftAllowsEditingAPendingReviewExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.updateDraft(contributorId, false, experience.getId(), sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftAllowsEditingACorrectionRequestedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.requestCorrection("Please add more detail to the teaser");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceFullResponse response = service.updateDraft(contributorId, false, experience.getId(), sampleRequest());

        assertThat(response.company()).isEqualTo("Acme");
        verify(experienceRepository).save(experience);
    }

    @Test
    void updateDraftRejectsAPublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.updateDraft(contributorId, false, experience.getId(), sampleRequest()))
                .isInstanceOf(InvalidStateException.class);
    }

    // --- updateDraft edit-history snapshotting ---

    /** Matches draftOwnedByContributor()'s field values exactly, so submitting this
     * through updateDraft is a genuine no-op edit. */
    private ExperienceRequest requestMatchingDraftOwnedByContributor() {
        return new ExperienceRequest(
                "Acme", "Backend Engineer", "L4", "Bengaluru", true,
                (short) 6, (short) 2026, ExperienceOutcome.OFFER, "teaser",
                "advice", (short) 3, "3 weeks", "35 LPA", null, null, false, null);
    }

    @Test
    void updateDraftSavesASnapshotWhenSomethingActuallyChanges() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.updateDraft(contributorId, false, experience.getId(), sampleRequest());

        verify(editSnapshotRepository).save(any());
    }

    @Test
    void updateDraftSkipsTheSnapshotWhenNothingActuallyChanged() {
        // A contributor re-saving the form unchanged shouldn't pad the edit history.
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.updateDraft(contributorId, false, experience.getId(), requestMatchingDraftOwnedByContributor());

        verify(editSnapshotRepository, never()).save(any());
    }

    // --- listEditHistory ---

    @Test
    void listEditHistoryRejectsUnknownExperience() {
        UUID missingId = UUID.randomUUID();
        when(experienceRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listEditHistory(contributorId, false, missingId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void listEditHistoryRejectsNonOwnerNonAdmin() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        assertThatThrownBy(() -> service.listEditHistory(UUID.randomUUID(), false, experience.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listEditHistoryAllowsAnAdminEvenWhenNotTheOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(editSnapshotRepository.findByExperienceIdOrderByRecordedAtDesc(experience.getId()))
                .thenReturn(List.of());

        List<ExperienceEditSnapshotResponse> history =
                service.listEditHistory(UUID.randomUUID(), true, experience.getId());

        assertThat(history).isEmpty();
    }

    @Test
    void listEditHistoryOrdersNewestFirstAndDiffsEachSnapshotAgainstWhatCameNext() {
        // Timeline: v1 ("Old Co" / "teaser v1") -> edited to v2 ("Mid Co" / "teaser v2",
        // snapshot A records v1) -> edited to the current live state ("Acme" / "teaser",
        // snapshot B records v2). listEditHistory should return [B, A] (newest first),
        // with B diffed against the current experience and A diffed against B.
        Experience current = draftOwnedByContributor(); // "Acme" / "teaser"

        Experience v2State = draftOwnedByContributor();
        v2State.applyEdits(
                "Mid Co", v2State.getRoleTitle(), v2State.getLevel(), v2State.getLocation(), v2State.isRemote(),
                v2State.getInterviewMonth(), v2State.getInterviewYear(), v2State.getOutcome(), "teaser v2",
                v2State.getPrepAdvice(), v2State.getOverallDifficulty(), v2State.getTimeline(), v2State.getCompensation(),
                v2State.getConfidentialNote());
        ExperienceEditSnapshot snapshotB = new ExperienceEditSnapshot(UUID.randomUUID(), v2State);

        Experience v1State = draftOwnedByContributor();
        v1State.applyEdits(
                "Old Co", v1State.getRoleTitle(), v1State.getLevel(), v1State.getLocation(), v1State.isRemote(),
                v1State.getInterviewMonth(), v1State.getInterviewYear(), v1State.getOutcome(), "teaser v1",
                v1State.getPrepAdvice(), v1State.getOverallDifficulty(), v1State.getTimeline(), v1State.getCompensation(),
                v1State.getConfidentialNote());
        ExperienceEditSnapshot snapshotA = new ExperienceEditSnapshot(UUID.randomUUID(), v1State);

        when(experienceRepository.findById(current.getId())).thenReturn(Optional.of(current));
        when(editSnapshotRepository.findByExperienceIdOrderByRecordedAtDesc(current.getId()))
                .thenReturn(List.of(snapshotB, snapshotA)); // repo contract: newest first

        List<ExperienceEditSnapshotResponse> history =
                service.listEditHistory(contributorId, false, current.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(snapshotB.getId());
        assertThat(history.get(0).company()).isEqualTo("Mid Co");
        assertThat(history.get(0).changedFields()).containsExactlyInAnyOrder("Company", "Teaser");
        assertThat(history.get(1).id()).isEqualTo(snapshotA.getId());
        assertThat(history.get(1).company()).isEqualTo("Old Co");
        assertThat(history.get(1).changedFields()).containsExactlyInAnyOrder("Company", "Teaser");
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
    void addRoundAllowsACorrectionRequestedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.requestCorrection("Please add more detail");
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
    void uploadProofRejectsADisallowedContentType() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        MockMultipartFile file =
                new MockMultipartFile("file", "offer.exe", "application/x-msdownload", "content".getBytes());
        assertThatThrownBy(() -> service.uploadProof(contributorId, experience.getId(), file))
                .isInstanceOf(InvalidStateException.class);
        verify(proofDocumentRepository, never()).save(any());
        verify(proofStorageService, never()).store(any(), any(), any());
    }

    @Test
    void uploadProofAllowsAJpegImage() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofStorageService.store(eq(experience.getId()), eq("offer.jpg"), any(InputStream.class)))
                .thenReturn(new ProofStorageService.StoredFile("some/key.jpg", 1024L));

        MockMultipartFile file = new MockMultipartFile("file", "offer.jpg", "image/jpeg", "content".getBytes());
        var response = service.uploadProof(contributorId, experience.getId(), file);

        assertThat(response.fileName()).isEqualTo("offer.jpg");
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

    @Test
    void submitForReviewPublishesAFreeContributionInstantlyWithoutAProofDocument() {
        Experience experience = freeContributionDraftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(1L);
        // Deliberately never stubbing proofDocumentRepository.countByExperienceId — a free
        // contribution must not even check it, since nothing about that call should matter.

        ExperienceFullResponse response = service.submitForReview(contributorId, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.PUBLISHED);
        assertThat(response.publishedAt()).isNotNull();
        verify(proofDocumentRepository, never()).countByExperienceId(any());
    }

    @Test
    void submitForReviewStillRequiresARoundForAFreeContribution() {
        Experience experience = freeContributionDraftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(0L);

        assertThatThrownBy(() -> service.submitForReview(contributorId, experience.getId()))
                .isInstanceOf(InvalidStateException.class)
                .hasMessageContaining("round");
    }

    @Test
    void submitForReviewResubmitsACorrectionRequestedExperienceAndClearsTheCorrectionNotes() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.requestCorrection("Please add more detail to the teaser");
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(roundRepository.countByExperienceId(experience.getId())).thenReturn(1L);
        when(proofDocumentRepository.countByExperienceId(experience.getId())).thenReturn(1L);

        ExperienceFullResponse response = service.submitForReview(contributorId, experience.getId());

        assertThat(response.status()).isEqualTo(ExperienceStatus.PENDING_REVIEW);
        assertThat(response.correctionNotes()).isNull();
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
    void getPublicViewIncludesConfidentialNoteForTheOwner() {
        Experience experience = draftOwnedByContributor();
        experience.applyEdits(
                experience.getCompany(), experience.getRoleTitle(), experience.getLevel(), experience.getLocation(),
                experience.isRemote(), experience.getInterviewMonth(), experience.getInterviewYear(),
                experience.getOutcome(), experience.getTeaser(), experience.getPrepAdvice(),
                experience.getOverallDifficulty(), experience.getTimeline(), experience.getCompensation(),
                "Interviewer mentioned budget freeze — please verify before publishing");
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceViewResponse response = service.getPublicView(contributorId, false, experience.getId());

        assertThat(response.full().confidentialNote())
                .isEqualTo("Interviewer mentioned budget freeze — please verify before publishing");
    }

    @Test
    void getPublicViewHidesConfidentialNoteFromAPurchaserWhoIsNeitherOwnerNorAdmin() {
        Experience experience = draftOwnedByContributor();
        experience.applyEdits(
                experience.getCompany(), experience.getRoleTitle(), experience.getLevel(), experience.getLocation(),
                experience.isRemote(), experience.getInterviewMonth(), experience.getInterviewYear(),
                experience.getOutcome(), experience.getTeaser(), experience.getPrepAdvice(),
                experience.getOverallDifficulty(), experience.getTimeline(), experience.getCompensation(),
                "Interviewer mentioned budget freeze — please verify before publishing");
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        UUID viewerId = UUID.randomUUID();
        when(entitlementRepository.existsByUserIdAndExperienceId(viewerId, experience.getId())).thenReturn(true);

        ExperienceViewResponse response = service.getPublicView(viewerId, false, experience.getId());

        assertThat(response.entitled()).isTrue();
        assertThat(response.full().confidentialNote()).isNull();
    }

    /** The increment goes through the repository's atomic UPDATE rather than mutating the
     * entity and saving it — see ExperienceRepository#incrementViewCount for why (a
     * versioned read-modify-write turned two viewers' concurrent first views into a 409 for
     * one of them). Asserting on the call rather than on experience.getViewCount() is the
     * point: nothing about the in-memory entity should change here. */
    @Test
    void getPublicViewIncrementsViewCountOnASignedInViewersFirstView() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        UUID viewerId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        // Default stub (see setUp) returns 1 — a genuinely new (experience, viewer) pair.

        service.getPublicView(viewerId, false, experience.getId());

        verify(experienceViewRepository).recordView(any(), eq(experience.getId()), eq(viewerId));
        verify(experienceRepository).incrementViewCount(experience.getId());
        // Never through the versioned entity — that's what could collide.
        verify(experienceRepository, never()).save(any());
    }

    /** incrementViewCount clears the persistence context, so the instance loaded at the top
     * of getPublicView is detached and carries the pre-increment count. The response has to
     * be built from a re-read, or a viewer's own view wouldn't show up until their next
     * visit. */
    @Test
    void getPublicViewRereadsTheExperienceAfterCountingAView() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        UUID viewerId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.getPublicView(viewerId, false, experience.getId());

        verify(experienceRepository, times(2)).findById(experience.getId());
    }

    /** The whole point of the one-per-user dedup — see ExperienceView's Javadoc. A second
     * load by the same signed-in viewer (a repeat visit, or two near-simultaneous requests
     * for the same viewer, e.g. React StrictMode's double-invoked effects) must not double
     * the count. recordView returning 0 is exactly what a real
     * INSERT ... ON CONFLICT DO NOTHING does for a duplicate (experience, viewer) pair. */
    @Test
    void getPublicViewDoesNotIncrementViewCountOnARepeatViewByTheSameSignedInViewer() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        UUID viewerId = UUID.randomUUID();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(experienceViewRepository.recordView(any(), eq(experience.getId()), eq(viewerId))).thenReturn(0);

        service.getPublicView(viewerId, false, experience.getId());

        verify(experienceRepository, never()).incrementViewCount(any());
    }

    /** Guests have no reliable identity to dedupe against, so their views are never
     * recorded or counted at all — not "counted every time" (the old behavior), just not
     * counted, full stop. */
    @Test
    void getPublicViewDoesNotIncrementViewCountForAGuest() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.getPublicView(null, false, experience.getId());

        verify(experienceRepository, never()).incrementViewCount(any());
        verify(experienceViewRepository, never()).recordView(any(), any(), any());
    }

    @Test
    void getPublicViewDoesNotIncrementViewCountForAnUnpublishedExperienceViewedByItsOwner() {
        Experience experience = draftOwnedByContributor();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.getPublicView(contributorId, false, experience.getId());

        verify(experienceRepository, never()).incrementViewCount(any());
        verify(experienceViewRepository, never()).recordView(any(), any(), any());
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
    void getPublicViewGivesAnonymousViewerFullAccessToAFreePublishedExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markAsReference("https://example.com/writeup", "Example Blog");
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        ExperienceViewResponse response = service.getPublicView(null, false, experience.getId());

        assertThat(response.entitled()).isTrue();
        assertThat(response.full().isFree()).isTrue();
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

    /** ORDER BY now lives inside the native browse query (relevance ranking can't be a
     * Pageable Sort), so the service passes an UNSORTED PageRequest and the sort intent
     * rides along as the sortMode String param. The repository is mocked here, so these
     * assert on the params the service builds; the real SQL behavior (normalized contains,
     * trigram ranking) is covered by BrowseSearchIT against a live Postgres. */
    private void stubBrowseReturns(Page<Experience> page) {
        when(experienceRepository.browsePublished(
                        any(), any(), any(), any(), any(), any(), any(), any(),
                        org.mockito.ArgumentMatchers.anyDouble(), any(), any()))
                .thenReturn(page);
    }

    private static Page<Experience> pageOf(List<Experience> experiences, int size) {
        return new PageImpl<>(experiences, PageRequest.of(0, size), experiences.size());
    }

    @Test
    void browsePublishedMapsRepositoryPageToTeasers() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        stubBrowseReturns(pageOf(List.of(experience), 20));

        var response = service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items()).hasSize(1);
        assertThat(response.totalItems()).isEqualTo(1);
    }

    @Test
    void browsePublishedIncludesRoundCountPerExperience() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        stubBrowseReturns(pageOf(List.of(experience), 20));
        ExperienceRoundRepository.ExperienceRoundCount count = mock(ExperienceRoundRepository.ExperienceRoundCount.class);
        when(count.getExperienceId()).thenReturn(experience.getId());
        when(count.getRoundCount()).thenReturn(3L);
        when(roundRepository.countByExperienceIdIn(List.of(experience.getId()))).thenReturn(List.of(count));

        var response = service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).roundCount()).isEqualTo(3);
    }

    @Test
    void browsePublishedDefaultsRoundCountToZeroForExperienceWithNoRounds() {
        // An experience with zero rounds simply doesn't appear in the bulk count query's
        // result — the service should default it to 0, not throw or leave it null.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        stubBrowseReturns(pageOf(List.of(experience), 20));
        when(roundRepository.countByExperienceIdIn(List.of(experience.getId()))).thenReturn(List.of());

        var response = service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).roundCount()).isZero();
    }

    @Test
    void browsePublishedSkipsRoundCountQueryForAnEmptyPage() {
        stubBrowseReturns(pageOf(List.of(), 20));

        var response = service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items()).isEmpty();
        verify(roundRepository, never()).countByExperienceIdIn(any());
    }

    @Test
    void browsePublishedCapsPageSizeAtOneHundred() {
        stubBrowseReturns(pageOf(List.of(), 100));

        service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 500);

        // Sort now lives in the SQL, so the PageRequest is unsorted — PageRequest.of(0, 100)
        // is exactly the unsorted request the service builds.
        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), eq(PageRequest.of(0, 100)));
    }

    /** The lower bounds matter as much as the upper one: PageRequest.of throws
     * IllegalArgumentException on a negative page or a size below 1, which nothing here
     * handles specially — it would come back as a 500 for what's really just a malformed
     * query string (a stale bookmark, a hand-edited URL). Clamped rather than rejected, the
     * same forgiving posture resolveSortMode() takes on an unknown sort value. */
    @Test
    void browsePublishedClampsNegativePageAndSizeInsteadOfBlowingUp() {
        stubBrowseReturns(pageOf(List.of(), 1));

        service.browsePublished(null, null, null, null, null, null, null, "newest", -3, -20);

        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), eq(PageRequest.of(0, 1)));
    }

    @Test
    void browsePublishedClampsAZeroSizeToOne() {
        stubBrowseReturns(pageOf(List.of(), 1));

        service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 0);

        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), eq(PageRequest.of(0, 1)));
    }

    @Test
    void browsePublishedTreatsBlankAndPunctuationOnlyFiltersAsNull() {
        stubBrowseReturns(pageOf(List.of(), 20));

        // Blank/whitespace ("  ", "") and punctuation-only ("  -  ") filters normalize to
        // nothing and become null, so the query takes each IS NULL branch rather than matching
        // on an empty pattern. A blank search collapses all three search params to null too.
        service.browsePublished(null, "  ", "", "  -  ", null, null, "  ", "newest", 0, 20);

        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), any());
    }

    @Test
    void browsePublishedNormalizesFilterValuesToPunctuationInsensitiveContainsPatterns() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, "Acme Corp", "Backend Engineer", "SDE-3", null, null, null, "newest", 0, 20);

        // Tier 1: each filter is lower-cased, stripped to alphanumerics, and wrapped as a
        // contains pattern — so "SDE-3" is queried as "%sde3%" and matches "SDE 3"/"sde3".
        verify(experienceRepository).browsePublished(
                eq("%acmecorp%"), eq("%backendengineer%"), eq("%sde3%"),
                eq(null), eq(null), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), any());
    }

    @Test
    void browsePublishedBuildsNormalizedAndSubstringSearchParams() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, null, "Backend Eng", "newest", 0, 20);

        // Tier 2: searchTerm is the lowered raw form (for similarity()), searchContains is the
        // normalized contains (for the company/role normalized columns), searchLike is a
        // lowered substring (for the teaser).
        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(null),
                eq("backend eng"), eq("%backendeng%"), eq("%backend eng%"),
                eq(SIMILARITY_THRESHOLD), eq("newest"), any());
    }

    @Test
    void browsePublishedPassesTheIsFreeFilterThrough() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, Boolean.TRUE, null, "newest", 0, 20);

        verify(experienceRepository).browsePublished(
                eq(null), eq(null), eq(null), eq(null), eq(Boolean.TRUE), eq(null), eq(null), eq(null),
                eq(SIMILARITY_THRESHOLD), eq("newest"), any());
    }

    @Test
    void browsePublishedMapsPriceLowSortToItsSortMode() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, null, null, "priceLow", 0, 20);

        verify(experienceRepository).browsePublished(
                any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), eq("priceLow"), any());
    }

    @Test
    void browsePublishedMapsPriceHighSortToItsSortMode() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, null, null, "priceHigh", 0, 20);

        verify(experienceRepository).browsePublished(
                any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), eq("priceHigh"), any());
    }

    @Test
    void browsePublishedMapsMostViewedSortToItsSortMode() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, null, null, "mostViewed", 0, 20);

        verify(experienceRepository).browsePublished(
                any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), eq("mostViewed"), any());
    }

    @Test
    void browsePublishedFallsBackToNewestSortModeForAnUnrecognizedSortValue() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(null, null, null, null, null, null, null, "bogus", 0, 20);

        verify(experienceRepository).browsePublished(
                any(), any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), eq("newest"), any());
    }

    @Test
    void browsePublishedMarksEverythingUnlockedFalseForAGuest() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        stubBrowseReturns(pageOf(List.of(experience), 20));

        var response = service.browsePublished(null, null, null, null, null, null, null, "newest", 0, 20);

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
        stubBrowseReturns(pageOf(List.of(unlocked, locked), 20));
        when(entitlementRepository.findExperienceIdsByUserIdAndExperienceIdIn(
                        eq(viewerId), eq(List.of(unlocked.getId(), locked.getId()))))
                .thenReturn(List.of(unlocked.getId()));

        var response = service.browsePublished(viewerId, null, null, null, null, null, null, "newest", 0, 20);

        assertThat(response.items().get(0).unlocked()).isTrue();
        assertThat(response.items().get(1).unlocked()).isFalse();
    }

    @Test
    void browsePublishedSkipsEntitlementQueryForAnEmptyPage() {
        stubBrowseReturns(pageOf(List.of(), 20));

        service.browsePublished(UUID.randomUUID(), null, null, null, null, null, null, "newest", 0, 20);

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

        ExperienceFullResponse response = service.updateDraft(contributorId, false, experience.getId(), sampleRequest());

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

    // --- email verification gate ---

    /** The gate is checked at the very start of the contributor flow, before any of the
     * request is looked at — an unconfirmed user shouldn't get as far as writing a teaser
     * and then be told no. */
    @Test
    void createDraftIsBlockedForAnUnconfirmedEmailAddress() {
        doThrow(new EmailNotVerifiedException("Confirm your email address before submitting an experience."))
                .when(emailVerificationGuard).requireVerified(eq(contributorId), any());

        assertThatThrownBy(() -> service.createDraft(contributorId, false, sampleRequest()))
                .isInstanceOf(EmailNotVerifiedException.class);

        verify(experienceRepository, never()).save(any());
    }

    /** Guarded independently of createDraft: a draft can predate the gate, and this is the
     * step that puts work in front of an admin and creates a payout liability. */
    @Test
    void submitForReviewIsBlockedForAnUnconfirmedEmailAddress() {
        doThrow(new EmailNotVerifiedException("Confirm your email address before submitting an experience."))
                .when(emailVerificationGuard).requireVerified(eq(contributorId), any());

        assertThatThrownBy(() -> service.submitForReview(contributorId, UUID.randomUUID()))
                .isInstanceOf(EmailNotVerifiedException.class);
    }

    /** Reading is deliberately not gated — an unconfirmed account can still look around, and
     * gating browse would cost signups without protecting anything. */
    @Test
    void readingAnExperienceIsNotGatedOnEmailVerification() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.publish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));

        service.getPublicView(UUID.randomUUID(), false, experience.getId());

        verify(emailVerificationGuard, never()).requireVerified(any(), any());
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
        verify(editSnapshotRepository).deleteByExperienceId(experience.getId());
        verify(experienceViewRepository).deleteByExperienceId(experience.getId());
        verify(experienceRepository).delete(experience);
    }

    /** Regression test, same shape as the rejected-experience one below: experience_views'
     * FK to experiences doesn't cascade either (see V10), so a published-then-unpublished
     * experience that anyone signed-in ever opened couldn't be deleted at all — the delete
     * failed on the constraint and surfaced as an opaque 409. Neither of the two guards in
     * deleteExperience catches this case: a free contribution has no entitlement (nothing to
     * buy) and no payout row (approve() skips those for free submissions), so it sails past
     * both and straight into the database error. */
    @Test
    void deleteExperienceCleansUpRecordedViewsSoAPreviouslyPublishedOneCanBeDeleted() {
        Experience experience = freeContributionDraftOwnedByContributor();
        experience.publish();
        experience.unpublish();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByExperienceId(experience.getId())).thenReturn(List.of());

        service.deleteExperience(contributorId, experience.getId());

        verify(experienceViewRepository).deleteByExperienceId(experience.getId());
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
    void deleteExperienceWorksOnAPendingReviewExperienceToo() {
        // A contributor can withdraw a submission before an admin has acted on it, not
        // just delete a draft or a rejected one — same window as requireContentEditable.
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        when(experienceRepository.findById(experience.getId())).thenReturn(Optional.of(experience));
        when(proofDocumentRepository.findByExperienceId(experience.getId())).thenReturn(List.of());

        service.deleteExperience(contributorId, experience.getId());

        verify(experienceRepository).delete(experience);
    }

    @Test
    void deleteExperienceWorksOnACorrectionRequestedExperienceToo() {
        Experience experience = draftOwnedByContributor();
        experience.markPendingReview();
        experience.requestCorrection("Please add more detail");
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
