package com.knowyourinterview.api.experience;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.knowyourinterview.api.auth.EmailVerificationGuard;
import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.common.PagedResponse;
import com.knowyourinterview.api.experience.dto.ExperienceEditSnapshotResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceTeaserResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.payment.EntitlementRepository;
import com.knowyourinterview.api.payout.PayoutRepository;

@Service
public class ExperienceService {

    private final ExperienceRepository experienceRepository;
    private final ExperienceRoundRepository roundRepository;
    private final ProofDocumentRepository proofDocumentRepository;
    private final ProofStorageService proofStorageService;
    private final EntitlementRepository entitlementRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final PayoutRepository payoutRepository;
    private final ExperienceResponseAssembler responseAssembler;
    private final ExperienceEditSnapshotRepository editSnapshotRepository;
    private final ExperienceViewRepository experienceViewRepository;
    private final EmailVerificationGuard emailVerificationGuard;
    private final int defaultPricePaise;
    private final int maxPageSize;

    /** Proof documents are offer letters / interview invites — PDFs and photos of physical
     * documents cover the real-world cases. Anything else (executables, scripts, archives)
     * is rejected outright rather than trusting the client-supplied content type alone;
     * this is a defense-in-depth allow-list, not a full file-sniffing check. */
    private static final Set<String> ALLOWED_PROOF_CONTENT_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/webp", "image/heic", "image/heif");

    public ExperienceService(
            ExperienceRepository experienceRepository,
            ExperienceRoundRepository roundRepository,
            ProofDocumentRepository proofDocumentRepository,
            ProofStorageService proofStorageService,
            EntitlementRepository entitlementRepository,
            ReviewLogRepository reviewLogRepository,
            PayoutRepository payoutRepository,
            ExperienceResponseAssembler responseAssembler,
            ExperienceEditSnapshotRepository editSnapshotRepository,
            ExperienceViewRepository experienceViewRepository,
            EmailVerificationGuard emailVerificationGuard,
            @Value("${app.pricing.default-price-paise}") int defaultPricePaise,
            @Value("${app.pagination.max-page-size:100}") int maxPageSize) {
        this.experienceRepository = experienceRepository;
        this.roundRepository = roundRepository;
        this.proofDocumentRepository = proofDocumentRepository;
        this.proofStorageService = proofStorageService;
        this.entitlementRepository = entitlementRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.payoutRepository = payoutRepository;
        this.responseAssembler = responseAssembler;
        this.editSnapshotRepository = editSnapshotRepository;
        this.experienceViewRepository = experienceViewRepository;
        this.emailVerificationGuard = emailVerificationGuard;
        this.defaultPricePaise = defaultPricePaise;
        this.maxPageSize = maxPageSize;
    }

    /**
     * A non-blank sourceUrl marks this as a "reference a public source" submission —
     * summarizing/linking to an already-public interview writeup instead of the
     * contributor's own account. These are admin-only (regular contributors never see this
     * option — see SubmissionWorkspace.tsx — but the check is enforced here too, not just
     * hidden in the UI) and always free: no platform price is assigned, and
     * getPublicView/PurchaseService both treat a free, published experience as open to
     * everyone, no paywall or entitlement needed. sourceUrl/sourceName are set once here and
     * are immutable afterward — updateDraft/applyEdits never touches them.
     * <p>
     * Separately, req.freeContribution() lets ANY contributor (no admin check) opt their own
     * write-up into the same free/no-price treatment — but unlike a reference submission, a
     * free contribution also skips admin review entirely (see submitForReview) rather than
     * just skipping payment. The two are mutually exclusive; a sourceUrl always wins if both
     * are somehow set, since that's the admin-only, still-reviewed path.
     */
    @Transactional
    public ExperienceFullResponse createDraft(UUID contributorId, boolean actorIsAdmin, ExperienceRequest req) {
        // Gated at the very start of the contributor flow rather than only at submit time,
        // so an unconfirmed user finds out before writing a long teaser, not after.
        emailVerificationGuard.requireVerified(contributorId, "submitting an experience");
        String sourceUrl = blankToNull(req.sourceUrl());
        String sourceName = blankToNull(req.sourceName());
        boolean isReference = sourceUrl != null;
        if (isReference) {
            if (!actorIsAdmin) {
                throw new ForbiddenException("Only admins can submit an experience that references a public source");
            }
            if (sourceName == null) {
                throw new InvalidStateException("Source site/platform is required when referencing a public source");
            }
        }
        boolean isFreeContribution = !isReference && req.freeContribution();

        Experience experience = new Experience(
                UUID.randomUUID(), contributorId, req.company(), req.roleTitle(), req.level(), req.location(),
                req.isRemote(), req.interviewMonth(), req.interviewYear(), req.outcome(), req.teaser(),
                req.prepAdvice(), req.overallDifficulty(), req.timeline(), req.compensation(),
                blankToNull(req.confidentialNote()),
                (isReference || isFreeContribution) ? 0 : defaultPricePaise);
        if (isReference) {
            experience.markAsReference(sourceUrl, sourceName);
        } else if (isFreeContribution) {
            experience.markAsFreeContribution();
        }
        experienceRepository.save(experience);
        return responseAssembler.toFullResponse(experience);
    }

    /** Before applying the edit, saves a snapshot of the fields as they stood right up
     * until now — but only if something in `req` actually differs from the current
     * values. A contributor re-saving the form unchanged (e.g. just to check something)
     * shouldn't pad the edit history with a no-op entry. See listEditHistory(), which
     * turns the resulting sequence of snapshots into a diffed history view.
     * <p>
     * Owner or admin — an admin editing directly is part of the correction-requested
     * flow (see AdminReviewService#requestCorrection): the edit applies immediately and
     * shows up in the same edit-history log a contributor's own edit would, there's no
     * separate "admin edit" staging step. confidentialNote is applied like any other
     * field here but deliberately isn't part of the edit-history diff — see FieldValues. */
    @Transactional
    public ExperienceFullResponse updateDraft(
            UUID actorId, boolean actorIsAdmin, UUID experienceId, ExperienceRequest req) {
        Experience experience = getOwnedOrAdmin(actorId, actorIsAdmin, experienceId);
        requireContentEditable(experience, "A published experience can't be edited directly — unpublish it first");
        if (!diffFields(FieldValues.of(experience), FieldValues.of(req)).isEmpty()) {
            editSnapshotRepository.save(new ExperienceEditSnapshot(UUID.randomUUID(), experience));
        }
        experience.applyEdits(
                req.company(), req.roleTitle(), req.level(), req.location(), req.isRemote(), req.interviewMonth(),
                req.interviewYear(), req.outcome(), req.teaser(), req.prepAdvice(), req.overallDifficulty(),
                req.timeline(), req.compensation(), blankToNull(req.confidentialNote()));
        experienceRepository.save(experience);
        return responseAssembler.toFullResponse(experience);
    }

    /** Owner or admin — matches downloadProof's visibility pattern. Newest first; each
     * entry is diffed against whatever state came right after it (a newer snapshot, or
     * the current live experience for the most recent one), so "changedFields" always
     * describes what that particular edit changed. Rounds aren't covered — see
     * ExperienceEditSnapshot's Javadoc for why. */
    @Transactional(readOnly = true)
    public List<ExperienceEditSnapshotResponse> listEditHistory(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        if (!isOwner && !viewerIsAdmin) {
            throw new ForbiddenException("You don't have permission to view this experience's edit history");
        }
        List<ExperienceEditSnapshot> snapshots =
                editSnapshotRepository.findByExperienceIdOrderByRecordedAtDesc(experienceId);
        List<ExperienceEditSnapshotResponse> result = new ArrayList<>(snapshots.size());
        FieldValues after = FieldValues.of(experience);
        for (ExperienceEditSnapshot snapshot : snapshots) {
            FieldValues before = FieldValues.of(snapshot);
            result.add(ExperienceEditSnapshotResponse.from(snapshot, diffFields(before, after)));
            after = before;
        }
        return result;
    }

    @Transactional
    public ExperienceRoundResponse addRound(UUID contributorId, UUID experienceId, RoundRequest req) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Rounds can't be added to a published experience — unpublish it first");
        short nextNumber = (short) (roundRepository.countByExperienceId(experienceId) + 1);
        ExperienceRound round = new ExperienceRound(
                UUID.randomUUID(), experienceId, nextNumber, req.roundType(), req.durationMinutes(),
                req.questionsAsked(), joinTags(req.topicsTags()), req.approach(), req.interviewerBehavior(),
                req.difficulty());
        roundRepository.save(round);
        return ExperienceRoundResponse.from(round);
    }

    /** Edits an existing round's content in place — same requireContentEditable window as
     * addRound, but without a remove-then-re-add round trip. roundNumber, id, and
     * experienceId are untouched; only the descriptive fields change. */
    @Transactional
    public ExperienceRoundResponse updateRound(UUID contributorId, UUID experienceId, UUID roundId, RoundRequest req) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Rounds can't be edited on a published experience — unpublish it first");
        ExperienceRound round = roundRepository.findByIdAndExperienceId(roundId, experienceId)
                .orElseThrow(() -> new NotFoundException("Round not found"));
        round.applyEdits(
                req.roundType(), req.durationMinutes(), req.questionsAsked(), joinTags(req.topicsTags()),
                req.approach(), req.interviewerBehavior(), req.difficulty());
        roundRepository.save(round);
        return ExperienceRoundResponse.from(round);
    }

    @Transactional
    public void deleteRound(UUID contributorId, UUID experienceId, UUID roundId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Rounds can't be removed from a published experience — unpublish it first");
        roundRepository.deleteByIdAndExperienceId(roundId, experienceId);
    }

    /** Owner-only, editable-only. Deletes both the DB row and the stored file so nothing
     * orphans on disk (or in S3, once that swap happens). */
    @Transactional
    public void deleteProof(UUID contributorId, UUID experienceId, UUID proofId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Proof documents can't be removed from a published experience — unpublish it first");
        ProofDocument doc = proofDocumentRepository.findByIdAndExperienceId(proofId, experienceId)
                .orElseThrow(() -> new NotFoundException("Proof document not found"));
        proofDocumentRepository.delete(doc);
        // Delete the file only after the row-removal commits — a rollback would restore the
        // row, and we must not have already deleted the file it points at (H1: keep disk and
        // DB from drifting).
        deleteFileAfterCommit(doc.getStorageKey());
    }

    /** Owner-only. Same window as requireContentEditable — DRAFT, PENDING_REVIEW, or
     * REJECTED — so a contributor can withdraw a submission they no longer want reviewed
     * without waiting for an admin to act on it first, not just delete a draft or a
     * rejected one. (This used to be DRAFT-or-REJECTED-only, on the theory that deleting
     * while PENDING_REVIEW was a bigger action than editing content mid-review. In
     * practice contributors wanted a real way to pull a submission back rather than wait
     * out a review they'd changed their mind about, so this now matches the same window
     * everything else content-related already uses.) Deletes the experience along with
     * its rounds, proof documents (DB rows and stored files), and review-log history — a
     * rejected submission the contributor doesn't want to fix, a draft they abandoned, or
     * a pending one they're withdrawing before a verdict comes back.
     *
     * Two extra guards beyond the status check: a DRAFT can also mean "this was
     * PUBLISHED and got unpublished for an edit" (see unpublish()), so it can carry real
     * purchase/entitlement/payout history even though its current status looks like a
     * never-submitted draft. Deleting that would either corrupt a paying viewer's access
     * or silently drop money owed to the contributor, and either row existing blocks the
     * delete at the database level anyway (their foreign keys aren't cascading, by
     * design — this isn't data anyone should lose to a cascade). Both checks turn that
     * into a clear error instead of a raw constraint-violation failure. */
    @Transactional
    public void deleteExperience(UUID contributorId, UUID experienceId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "A published experience can't be deleted — unpublish it first");
        if (entitlementRepository.existsByExperienceId(experienceId)) {
            throw new InvalidStateException(
                    "This experience has been purchased and can't be deleted — unpublish it if you need to fix something, don't delete it");
        }
        if (payoutRepository.existsByExperienceId(experienceId)) {
            throw new InvalidStateException(
                    "This experience has a payout on record and can't be deleted");
        }
        List<ProofDocument> proofDocs = proofDocumentRepository.findByExperienceId(experienceId);
        proofDocumentRepository.deleteAll(proofDocs);
        roundRepository.deleteByExperienceId(experienceId);
        reviewLogRepository.deleteByExperienceId(experienceId);
        editSnapshotRepository.deleteByExperienceId(experienceId);
        // Must be explicit: experience_views' FK doesn't cascade (V10), so leaving these
        // behind fails the delete on a constraint violation — which the exception handler
        // turns into an opaque 409. Reachable for a free/reference submission that was
        // published, viewed by a signed-in user, then unpublished: neither the entitlement
        // nor the payout guard above catches it, so this was the first thing to break.
        experienceViewRepository.deleteByExperienceId(experienceId);
        experienceRepository.delete(experience);
        // Delete the stored files only after the whole delete commits — if the tx rolls
        // back the rows survive and their files must still be on disk (H1).
        proofDocs.forEach(doc -> deleteFileAfterCommit(doc.getStorageKey()));
    }

    /** Pulls a PUBLISHED experience back to DRAFT so its owner can fix it and resubmit
     * through review. Allowed for the owning contributor or any admin — moderation power
     * for admins, self-service correction for contributors. The payout ledger row created
     * at approval time is untouched (money already moved or is owed regardless of whether
     * the listing is currently live), and existing purchasers keep full access — see
     * getPublicView, which checks entitlement/ownership independent of current status. */
    @Transactional
    public ExperienceFullResponse unpublish(UUID actorId, boolean actorIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        boolean isOwner = actorId != null && actorId.equals(experience.getContributorId());
        if (!isOwner && !actorIsAdmin) {
            throw new ForbiddenException("You don't have permission to unpublish this experience");
        }
        if (experience.getStatus() != ExperienceStatus.PUBLISHED) {
            throw new InvalidStateException("Only a published experience can be unpublished");
        }
        experience.unpublish();
        experienceRepository.save(experience);
        return responseAssembler.toFullResponse(experience);
    }

    @Transactional
    public ProofDocumentResponse uploadProof(UUID contributorId, UUID experienceId, MultipartFile file) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Proof can't be uploaded to a published experience — unpublish it first");
        if (file.isEmpty()) {
            throw new InvalidStateException("Uploaded file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_PROOF_CONTENT_TYPES.contains(contentType.toLowerCase(java.util.Locale.ROOT))) {
            throw new InvalidStateException(
                    "Unsupported file type — upload a PDF or an image (PNG, JPEG, WEBP, HEIC)");
        }
        try (InputStream in = file.getInputStream()) {
            ProofStorageService.StoredFile stored = proofStorageService.store(experienceId, file.getOriginalFilename(), in);
            // The file is on disk now but the row isn't committed yet — if this tx rolls
            // back, the ProofDocument row won't exist, so register a compensating delete so
            // the file doesn't orphan on disk (H1).
            deleteFileIfRolledBack(stored.storageKey());
            ProofDocument proof = new ProofDocument(
                    UUID.randomUUID(), experienceId, stored.storageKey(), file.getOriginalFilename(),
                    file.getContentType() == null ? "application/octet-stream" : file.getContentType());
            proofDocumentRepository.save(proof);
            return ProofDocumentResponse.from(proof);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    /** Works from DRAFT (first submission) or REJECTED (resubmission after fixing what an
     * admin flagged) — either way it lands back in PENDING_REVIEW, and markPendingReview()
     * clears any stale rejection reason from a prior round.
     * <p>
     * A free contribution (see Experience#isSelfFreeContribution) is the one exception: it
     * skips PENDING_REVIEW and admin approval entirely and publishes immediately, since
     * there's no payout or paywall riding on it that would need a human check first. It only
     * needs at least one round — the proof-document requirement exists for an admin to
     * verify, and nobody reviews a free contribution, so there's nothing for a proof document
     * to accomplish there. */
    @Transactional
    public ExperienceFullResponse submitForReview(UUID contributorId, UUID experienceId) {
        // Also guarded here, not just at createDraft: a draft may predate the gate (or
        // predate the account becoming unverified), and this is the step that actually puts
        // work in front of an admin and creates a payout liability.
        emailVerificationGuard.requireVerified(contributorId, "submitting an experience");
        Experience experience = getOwned(contributorId, experienceId);
        requireResubmittable(experience,
                "Only a draft, rejected, or correction-requested experience can be submitted for review");

        if (roundRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Add at least one interview round before submitting");
        }

        if (experience.isSelfFreeContribution()) {
            experience.publish();
            experienceRepository.save(experience);
            return responseAssembler.toFullResponse(experience);
        }

        if (proofDocumentRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Upload at least one proof document before submitting");
        }

        experience.markPendingReview();
        experienceRepository.save(experience);
        return responseAssembler.toFullResponse(experience);
    }

    @Transactional(readOnly = true)
    public List<ExperienceFullResponse> listMine(UUID contributorId) {
        // Batched build — one query each for rounds/proofs/unlock-counts across all of the
        // contributor's experiences, instead of three per row.
        return responseAssembler.buildMany(
                experienceRepository.findByContributorIdOrderByCreatedAtDesc(contributorId));
    }

    @Transactional(readOnly = true)
    public ExperienceFullResponse getMine(UUID contributorId, UUID experienceId) {
        return responseAssembler.toFullResponse(getOwned(contributorId, experienceId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExperienceTeaserResponse> browsePublished(
            UUID viewerId, String company, String roleTitle, String level, Short year, Boolean isFree,
            String search, String sort, int page, int size) {
        // Both bounds matter, not just the upper one: PageRequest.of throws
        // IllegalArgumentException on a negative page or a size below 1, which the catch-all
        // exception handler would turn into a 500 for what is really a malformed request.
        // Clamping instead of rejecting keeps a stale bookmark or a hand-edited query string
        // from erroring at all — same forgiving posture resolveSort() takes on a sort value
        // it doesn't recognise.
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), maxPageSize);
        Page<Experience> result = experienceRepository.browsePublished(
                blankToNull(company), blankToNull(roleTitle), blankToNull(level), year, isFree, searchPattern(search),
                PageRequest.of(safePage, safeSize, resolveSort(sort)));
        List<UUID> ids = result.getContent().stream().map(Experience::getId).toList();
        // An empty IN (...) list is invalid JPQL for most providers — skip the query(ies)
        // entirely for an empty page instead of sending a zero-length list.
        Map<UUID, Long> roundCounts = ids.isEmpty()
                ? Map.of()
                : roundRepository.countByExperienceIdIn(ids).stream()
                        .collect(Collectors.toMap(
                                ExperienceRoundRepository.ExperienceRoundCount::getExperienceId,
                                ExperienceRoundRepository.ExperienceRoundCount::getRoundCount));
        // A guest (viewerId == null) has nothing unlocked by definition — skip the query.
        Set<UUID> unlockedIds = (viewerId == null || ids.isEmpty())
                ? Set.of()
                : new HashSet<>(entitlementRepository.findExperienceIdsByUserIdAndExperienceIdIn(viewerId, ids));
        return PagedResponse.of(
                result.map(e -> ExperienceTeaserResponse.from(
                        e, roundCounts.getOrDefault(e.getId(), 0L), unlockedIds.contains(e.getId()) || e.isFree())));
    }

    /**
     * Public single-experience view. "entitled" (full content) is true for: the owning
     * contributor, an admin (needs it to review), or a viewer holding a real paid
     * Entitlement row (Phase 4). Everyone else gets the teaser if it's published, or a 404
     * if it isn't visible to them at all.
     *
     * The visibility check and the entitlement check used to be two separate gates, in an
     * order that meant a paying viewer who wasn't the owner/an admin got a 404 the instant
     * status wasn't PUBLISHED — which would have wrongly locked out existing purchasers the
     * moment an experience got unpublished for an edit. Entitlement now grants visibility
     * in its own right, independent of current status, so already-paid access survives an
     * unpublish/re-review cycle.
     * <p>
     * Also the sole place view_count gets incremented — a signed-in viewer's first ever
     * load of a PUBLISHED experience's detail page counts as a view; loading it again
     * later doesn't (see ExperienceView / ExperienceViewRepository#recordView, which is
     * what makes this one-per-user instead of once-per-page-load). Counts regardless of
     * whether they get the full write-up or just the teaser, and regardless of whether
     * they're the owner, an admin, or a purchaser — but only if they're signed in; a
     * guest's view is never recorded or counted, since there's no reliable identity to
     * dedupe a guest against. Not readOnly any more because of that write.
     * <p>
     * The increment goes through ExperienceRepository#incrementViewCount (an atomic,
     * unversioned UPDATE) rather than the managed entity — see that method for why a
     * read-modify-write here used to turn two concurrent first-views into a 409 for one of
     * the two viewers. The re-read afterwards is what puts the fresh count in the response.
     */
    @Transactional
    public ExperienceViewResponse getPublicView(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));

        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        boolean hasPurchased = viewerId != null
                && !isOwner
                && !viewerIsAdmin
                && entitlementRepository.existsByUserIdAndExperienceId(viewerId, experienceId);
        // A free (admin-authored reference) experience has no paywall at all once
        // published — open to any viewer, including guests, same as the teaser already is.
        boolean freeAndPublished = experience.isFree() && experience.getStatus() == ExperienceStatus.PUBLISHED;

        boolean visible = isOwner || viewerIsAdmin || hasPurchased || experience.getStatus() == ExperienceStatus.PUBLISHED;
        if (!visible) {
            throw new NotFoundException("Experience not found");
        }

        if (experience.getStatus() == ExperienceStatus.PUBLISHED && viewerId != null) {
            boolean isFirstViewByThisViewer =
                    experienceViewRepository.recordView(UUID.randomUUID(), experienceId, viewerId) > 0;
            if (isFirstViewByThisViewer) {
                experienceRepository.incrementViewCount(experienceId);
                // incrementViewCount clears the persistence context, so the instance loaded
                // above is detached and still holds the pre-increment count — re-read it so
                // the viewer's own view is reflected in the response they get back.
                experience = experienceRepository.findById(experienceId)
                        .orElseThrow(() -> new NotFoundException("Experience not found"));
            }
        }

        if (isOwner || viewerIsAdmin || hasPurchased || freeAndPublished) {
            // confidentialNote is for the submitter and admins only — a purchaser or a
            // free-viewer who's neither still reaches this fullAccess branch (they're
            // entitled to the content), but shouldn't see it.
            boolean includeConfidentialNote = isOwner || viewerIsAdmin;
            return ExperienceViewResponse.fullAccess(
                    responseAssembler.toFullResponse(experience, includeConfidentialNote));
        }
        // Reaching this branch means hasPurchased was false (otherwise we'd be in the
        // fullAccess branch above), so unlocked is always false here.
        return ExperienceViewResponse.teaserOnly(
                ExperienceTeaserResponse.from(experience, roundRepository.countByExperienceId(experienceId), false));
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

    /** Same lookup as getOwned, but also lets an admin through regardless of ownership —
     * used only by updateDraft, so an admin can edit a contributor's submission directly
     * as part of the correction-requested flow. */
    private Experience getOwnedOrAdmin(UUID actorId, boolean actorIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        boolean isOwner = actorId != null && actorId.equals(experience.getContributorId());
        if (!isOwner && !actorIsAdmin) {
            throw new ForbiddenException("You don't have permission to edit this experience");
        }
        return experience;
    }

    /** Content (fields, rounds, proof documents) can be edited any time before an
     * experience is either live or fully withdrawn: DRAFT (never submitted), PENDING_REVIEW
     * (submitted, awaiting a verdict — a contributor spotting a typo or wanting to add
     * detail shouldn't have to wait for a rejection first), REJECTED, or
     * CORRECTION_REQUESTED (sent back with a reason/notes to fix). Only PUBLISHED is
     * locked out — a live listing has to go through unpublish() first, which is a
     * bigger, more deliberate action than a content edit. */
    private void requireContentEditable(Experience experience, String message) {
        ExperienceStatus status = experience.getStatus();
        boolean editable = status == ExperienceStatus.DRAFT
                || status == ExperienceStatus.PENDING_REVIEW
                || status == ExperienceStatus.REJECTED
                || status == ExperienceStatus.CORRECTION_REQUESTED;
        if (!editable) {
            throw new InvalidStateException(message);
        }
    }

    /** Narrower than requireContentEditable — DRAFT, REJECTED, or CORRECTION_REQUESTED
     * only. Used by submitForReview: there's nothing to (re)submit while already
     * PENDING_REVIEW, so that one status stays out of scope even though deleteExperience
     * no longer does. */
    private void requireResubmittable(Experience experience, String message) {
        ExperienceStatus status = experience.getStatus();
        boolean resubmittable = status == ExperienceStatus.DRAFT
                || status == ExperienceStatus.REJECTED
                || status == ExperienceStatus.CORRECTION_REQUESTED;
        if (!resubmittable) {
            throw new InvalidStateException(message);
        }
    }

    /** Defers a best-effort file delete until the current transaction commits, so a
     * rollback never leaves the DB pointing at a file we've already removed. Runs
     * immediately if there's no active transaction (defensive — callers here are all
     * @Transactional). */
    private void deleteFileAfterCommit(String storageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    proofStorageService.delete(storageKey);
                }
            });
        } else {
            proofStorageService.delete(storageKey);
        }
    }

    /** Registers a compensating delete that runs only if the current transaction rolls
     * back — used right after storing an upload, so a file whose DB row never commits
     * doesn't orphan on disk. */
    private void deleteFileIfRolledBack(String storageKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        proofStorageService.delete(storageKey);
                    }
                }
            });
        }
    }

    /** The subset of an experience's fields that EditDetailsForm edits and that edit
     * history tracks — a common shape so updateDraft and listEditHistory can compare an
     * Experience, an ExperienceEditSnapshot, and an incoming ExperienceRequest against
     * each other without three separate ad-hoc comparisons. */
    private record FieldValues(
            String company, String roleTitle, String level, String location, boolean remote,
            Short interviewMonth, Short interviewYear, ExperienceOutcome outcome, String teaser,
            String prepAdvice, Short overallDifficulty, String timeline, String compensation) {

        static FieldValues of(Experience e) {
            return new FieldValues(
                    e.getCompany(), e.getRoleTitle(), e.getLevel(), e.getLocation(), e.isRemote(),
                    e.getInterviewMonth(), e.getInterviewYear(), e.getOutcome(), e.getTeaser(),
                    e.getPrepAdvice(), e.getOverallDifficulty(), e.getTimeline(), e.getCompensation());
        }

        static FieldValues of(ExperienceEditSnapshot s) {
            return new FieldValues(
                    s.getCompany(), s.getRoleTitle(), s.getLevel(), s.getLocation(), s.isRemote(),
                    s.getInterviewMonth(), s.getInterviewYear(), s.getOutcome(), s.getTeaser(),
                    s.getPrepAdvice(), s.getOverallDifficulty(), s.getTimeline(), s.getCompensation());
        }

        static FieldValues of(ExperienceRequest r) {
            return new FieldValues(
                    r.company(), r.roleTitle(), r.level(), r.location(), r.isRemote(),
                    r.interviewMonth(), r.interviewYear(), r.outcome(), r.teaser(),
                    r.prepAdvice(), r.overallDifficulty(), r.timeline(), r.compensation());
        }
    }

    /** Human-readable labels for whichever of `before`'s fields differ in `after` — used
     * both to decide whether updateDraft has anything worth snapshotting, and to annotate
     * each edit-history entry with what that particular edit changed. */
    private List<String> diffFields(FieldValues before, FieldValues after) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.company(), after.company())) changed.add("Company");
        if (!Objects.equals(before.roleTitle(), after.roleTitle())) changed.add("Role title");
        if (!Objects.equals(before.level(), after.level())) changed.add("Level");
        if (!Objects.equals(before.location(), after.location())) changed.add("Location");
        if (before.remote() != after.remote()) changed.add("Remote");
        if (!Objects.equals(before.interviewMonth(), after.interviewMonth())) changed.add("Interview month");
        if (!Objects.equals(before.interviewYear(), after.interviewYear())) changed.add("Interview year");
        if (!Objects.equals(before.outcome(), after.outcome())) changed.add("Outcome");
        if (!Objects.equals(before.teaser(), after.teaser())) changed.add("Teaser");
        if (!Objects.equals(before.prepAdvice(), after.prepAdvice())) changed.add("Prep advice");
        if (!Objects.equals(before.overallDifficulty(), after.overallDifficulty())) changed.add("Overall difficulty");
        if (!Objects.equals(before.timeline(), after.timeline())) changed.add("Timeline");
        if (!Objects.equals(before.compensation(), after.compensation())) changed.add("Compensation");
        return changed;
    }

    private static String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : String.join(",", tags);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Builds the LIKE pattern the repository query expects, or null for "no search" —
     * wildcards and lowercasing happen here so the JPQL only has to do a plain LIKE. */
    private static String searchPattern(String search) {
        String trimmed = blankToNull(search);
        return trimmed == null ? null : "%" + trimmed.toLowerCase() + "%";
    }

    /** "newest" (default/unrecognized value) sorts by publishedAt descending; priceLow/
     * priceHigh sort by price; "mostViewed" sorts by the one-per-user view count (see
     * Experience#viewCount / ExperienceView) descending, publishedAt descending as the
     * tiebreak for experiences tied on views (most commonly a bunch of untouched 0s).
     * Falling back silently on an unrecognized value rather than throwing keeps a stale/
     * bookmarked "sort=" query param from breaking the page. */
    private static Sort resolveSort(String sort) {
        if ("priceLow".equals(sort)) {
            return Sort.by(Sort.Direction.ASC, "pricePaise");
        }
        if ("priceHigh".equals(sort)) {
            return Sort.by(Sort.Direction.DESC, "pricePaise");
        }
        if ("mostViewed".equals(sort)) {
            return Sort.by(Sort.Direction.DESC, "viewCount").and(Sort.by(Sort.Direction.DESC, "publishedAt"));
        }
        return Sort.by(Sort.Direction.DESC, "publishedAt");
    }
}
