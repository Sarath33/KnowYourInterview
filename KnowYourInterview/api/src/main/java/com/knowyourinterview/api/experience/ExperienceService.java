package com.knowyourinterview.api.experience;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.common.PagedResponse;
import com.knowyourinterview.api.experience.dto.ExperienceFullResponse;
import com.knowyourinterview.api.experience.dto.ExperienceRequest;
import com.knowyourinterview.api.experience.dto.ExperienceRoundResponse;
import com.knowyourinterview.api.experience.dto.ExperienceTeaserResponse;
import com.knowyourinterview.api.experience.dto.ExperienceViewResponse;
import com.knowyourinterview.api.experience.dto.ProofDocumentResponse;
import com.knowyourinterview.api.experience.dto.RoundRequest;
import com.knowyourinterview.api.payment.EntitlementRepository;

@Service
public class ExperienceService {

    private final ExperienceRepository experienceRepository;
    private final ExperienceRoundRepository roundRepository;
    private final ProofDocumentRepository proofDocumentRepository;
    private final ProofStorageService proofStorageService;
    private final EntitlementRepository entitlementRepository;
    private final ReviewLogRepository reviewLogRepository;
    private final PayoutRepository payoutRepository;
    private final int defaultPricePaise;

    public ExperienceService(
            ExperienceRepository experienceRepository,
            ExperienceRoundRepository roundRepository,
            ProofDocumentRepository proofDocumentRepository,
            ProofStorageService proofStorageService,
            EntitlementRepository entitlementRepository,
            ReviewLogRepository reviewLogRepository,
            PayoutRepository payoutRepository,
            @Value("${app.pricing.default-price-paise}") int defaultPricePaise) {
        this.experienceRepository = experienceRepository;
        this.roundRepository = roundRepository;
        this.proofDocumentRepository = proofDocumentRepository;
        this.proofStorageService = proofStorageService;
        this.entitlementRepository = entitlementRepository;
        this.reviewLogRepository = reviewLogRepository;
        this.payoutRepository = payoutRepository;
        this.defaultPricePaise = defaultPricePaise;
    }

    @Transactional
    public ExperienceFullResponse createDraft(UUID contributorId, ExperienceRequest req) {
        Experience experience = new Experience(
                UUID.randomUUID(), contributorId, req.company(), req.roleTitle(), req.level(), req.location(),
                req.isRemote(), req.interviewMonth(), req.interviewYear(), req.outcome(), req.teaser(),
                req.prepAdvice(), req.overallDifficulty(), req.timeline(), req.compensation(), defaultPricePaise);
        experienceRepository.save(experience);
        return toFullResponse(experience);
    }

    @Transactional
    public ExperienceFullResponse updateDraft(UUID contributorId, UUID experienceId, ExperienceRequest req) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "A published experience can't be edited directly — unpublish it first");
        experience.applyEdits(
                req.company(), req.roleTitle(), req.level(), req.location(), req.isRemote(), req.interviewMonth(),
                req.interviewYear(), req.outcome(), req.teaser(), req.prepAdvice(), req.overallDifficulty(),
                req.timeline(), req.compensation());
        experienceRepository.save(experience);
        return toFullResponse(experience);
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
        proofStorageService.delete(doc.getStorageKey());
    }

    /** Owner-only, DRAFT-or-REJECTED only (narrower than the content-editable window —
     * withdrawing a submission entirely while an admin may be actively reviewing it is a
     * bigger action than editing its content, so PENDING_REVIEW is deliberately excluded
     * here even though it's now editable). Deletes the experience along with its rounds,
     * proof documents (DB rows and stored files), and review-log history — a rejected
     * submission the contributor doesn't want to fix, or a draft they abandoned.
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
        requireDraftOrRejected(experience, "Only a draft or rejected experience can be deleted");
        if (entitlementRepository.existsByExperienceId(experienceId)) {
            throw new InvalidStateException(
                    "This experience has been purchased and can't be deleted — unpublish it if you need to fix something, don't delete it");
        }
        if (payoutRepository.existsByExperienceId(experienceId)) {
            throw new InvalidStateException(
                    "This experience has a payout on record and can't be deleted");
        }
        List<ProofDocument> proofDocs = proofDocumentRepository.findByExperienceId(experienceId);
        proofDocs.forEach(doc -> proofStorageService.delete(doc.getStorageKey()));
        proofDocumentRepository.deleteAll(proofDocs);
        roundRepository.deleteByExperienceId(experienceId);
        reviewLogRepository.deleteByExperienceId(experienceId);
        experienceRepository.delete(experience);
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
        return toFullResponse(experience);
    }

    @Transactional
    public ProofDocumentResponse uploadProof(UUID contributorId, UUID experienceId, MultipartFile file) {
        Experience experience = getOwned(contributorId, experienceId);
        requireContentEditable(experience, "Proof can't be uploaded to a published experience — unpublish it first");
        if (file.isEmpty()) {
            throw new InvalidStateException("Uploaded file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            ProofStorageService.StoredFile stored = proofStorageService.store(experienceId, file.getOriginalFilename(), in);
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
     * clears any stale rejection reason from a prior round. */
    @Transactional
    public ExperienceFullResponse submitForReview(UUID contributorId, UUID experienceId) {
        Experience experience = getOwned(contributorId, experienceId);
        requireDraftOrRejected(experience, "Only a draft or rejected experience can be submitted for review");

        if (roundRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Add at least one interview round before submitting");
        }
        if (proofDocumentRepository.countByExperienceId(experienceId) == 0) {
            throw new InvalidStateException("Upload at least one proof document before submitting");
        }

        experience.markPendingReview();
        experienceRepository.save(experience);
        return toFullResponse(experience);
    }

    @Transactional(readOnly = true)
    public List<ExperienceFullResponse> listMine(UUID contributorId) {
        return experienceRepository.findByContributorIdOrderByCreatedAtDesc(contributorId).stream()
                .map(this::toFullResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ExperienceFullResponse getMine(UUID contributorId, UUID experienceId) {
        return toFullResponse(getOwned(contributorId, experienceId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExperienceTeaserResponse> browsePublished(
            UUID viewerId, String company, String roleTitle, String level, Short year, String search, String sort,
            int page, int size) {
        Page<Experience> result = experienceRepository.browsePublished(
                blankToNull(company), blankToNull(roleTitle), blankToNull(level), year, searchPattern(search),
                PageRequest.of(page, Math.min(size, 100), resolveSort(sort)));
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
                        e, roundCounts.getOrDefault(e.getId(), 0L), unlockedIds.contains(e.getId()))));
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
     */
    @Transactional(readOnly = true)
    public ExperienceViewResponse getPublicView(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));

        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        boolean hasPurchased = viewerId != null
                && !isOwner
                && !viewerIsAdmin
                && entitlementRepository.existsByUserIdAndExperienceId(viewerId, experienceId);

        boolean visible = isOwner || viewerIsAdmin || hasPurchased || experience.getStatus() == ExperienceStatus.PUBLISHED;
        if (!visible) {
            throw new NotFoundException("Experience not found");
        }

        if (isOwner || viewerIsAdmin || hasPurchased) {
            return ExperienceViewResponse.fullAccess(toFullResponse(experience));
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

    /** Content (fields, rounds, proof documents) can be edited any time before an
     * experience is either live or fully withdrawn: DRAFT (never submitted), PENDING_REVIEW
     * (submitted, awaiting a verdict — a contributor spotting a typo or wanting to add
     * detail shouldn't have to wait for a rejection first), or REJECTED (sent back with a
     * reason). Only PUBLISHED is locked out — a live listing has to go through
     * unpublish() first, which is a bigger, more deliberate action than a content edit. */
    private void requireContentEditable(Experience experience, String message) {
        ExperienceStatus status = experience.getStatus();
        boolean editable = status == ExperienceStatus.DRAFT
                || status == ExperienceStatus.PENDING_REVIEW
                || status == ExperienceStatus.REJECTED;
        if (!editable) {
            throw new InvalidStateException(message);
        }
    }

    /** Narrower than requireContentEditable — DRAFT or REJECTED only, for the two actions
     * that don't make sense mid-review: submitForReview (there's nothing to (re)submit
     * while already PENDING_REVIEW) and deleteExperience (withdrawing a submission
     * entirely while an admin may be actively looking at it is a bigger action than
     * editing its content, so it's kept out of scope for PENDING_REVIEW). */
    private void requireDraftOrRejected(Experience experience, String message) {
        ExperienceStatus status = experience.getStatus();
        if (status != ExperienceStatus.DRAFT && status != ExperienceStatus.REJECTED) {
            throw new InvalidStateException(message);
        }
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
        long unlockCount = entitlementRepository.countByExperienceId(experience.getId());
        return ExperienceFullResponse.from(experience, rounds, proof, unlockCount);
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

    /** "newest" (default/unrecognized value) sorts by publishedAt descending; the other
     * two sort by price. Falling back silently on an unrecognized value rather than
     * throwing keeps a stale/bookmarked "sort=" query param from breaking the page. */
    private static Sort resolveSort(String sort) {
        if ("priceLow".equals(sort)) {
            return Sort.by(Sort.Direction.ASC, "pricePaise");
        }
        if ("priceHigh".equals(sort)) {
            return Sort.by(Sort.Direction.DESC, "pricePaise");
        }
        return Sort.by(Sort.Direction.DESC, "publishedAt");
    }
}
