package com.knowyourinterview.api.comment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.knowyourinterview.api.auth.EmailVerificationGuard;
import com.knowyourinterview.api.comment.dto.CommentResponse;
import com.knowyourinterview.api.comment.dto.CreateCommentRequest;
import com.knowyourinterview.api.common.ForbiddenException;
import com.knowyourinterview.api.common.InvalidStateException;
import com.knowyourinterview.api.common.NotFoundException;
import com.knowyourinterview.api.experience.Experience;
import com.knowyourinterview.api.experience.ExperienceRepository;
import com.knowyourinterview.api.experience.ExperienceStatus;
import com.knowyourinterview.api.payment.EntitlementRepository;
import com.knowyourinterview.api.user.User;
import com.knowyourinterview.api.user.UserRepository;

/**
 * Comments on an interview-experience page. Access is gated exactly like the write-up itself:
 * the "full access" predicate is the same one {@code ExperienceService#getPublicView} uses —
 * PUBLISHED and ({@code isFree} OR owner OR admin OR a held entitlement). A free+published
 * experience is open to everyone including guests; a paid one only to the owner, an admin, or
 * a purchaser.
 * <ul>
 *   <li><b>Read</b> requires full access (guests allowed only on free).</li>
 *   <li><b>Post</b> additionally requires a signed-in, email-verified account.</li>
 *   <li><b>Delete</b> is allowed to the comment's author or an admin, and is soft.</li>
 * </ul>
 * The controller layer is defence-in-depth over the {@code SecurityConfig} rules; this service
 * enforces the real rule regardless of how the request got here.
 */
@Service
public class CommentService {

    private static final int MAX_BODY_LENGTH = 4000;

    private final ExperienceRepository experienceRepository;
    private final EntitlementRepository entitlementRepository;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final EmailVerificationGuard emailVerificationGuard;

    public CommentService(
            ExperienceRepository experienceRepository,
            EntitlementRepository entitlementRepository,
            CommentRepository commentRepository,
            UserRepository userRepository,
            EmailVerificationGuard emailVerificationGuard) {
        this.experienceRepository = experienceRepository;
        this.entitlementRepository = entitlementRepository;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.emailVerificationGuard = emailVerificationGuard;
    }

    /** The comment tree for an experience: top-level comments newest-first, each with its
     * replies oldest-first. 404 if the experience isn't found or isn't PUBLISHED; 403 if the
     * viewer lacks full access (e.g. a paid experience they haven't unlocked). */
    @Transactional(readOnly = true)
    public List<CommentResponse> list(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = requirePublishedWithFullAccess(viewerId, viewerIsAdmin, experienceId);
        List<Comment> all = commentRepository.findByExperienceId(experienceId);
        return buildTree(all, viewerId, viewerIsAdmin, experience.getContributorId());
    }

    /** Posts a comment (or a reply). Requires full access plus a verified email. A reply's
     * {@code parentId} must be an existing, non-deleted, TOP-LEVEL comment of this same
     * experience — one level of nesting only. Returns the created node (replies empty). */
    @Transactional
    public CommentResponse create(
            UUID authorId, boolean authorIsAdmin, UUID experienceId, CreateCommentRequest req) {
        Experience experience = requirePublishedWithFullAccess(authorId, authorIsAdmin, experienceId);
        emailVerificationGuard.requireVerified(authorId, "commenting");

        String body = req.body() == null ? "" : req.body().trim();
        if (body.isEmpty()) {
            throw new InvalidStateException("Comment can't be empty");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            throw new InvalidStateException("Comment is too long (max " + MAX_BODY_LENGTH + " characters)");
        }

        UUID parentId = resolveParent(experienceId, req.parentId());

        Comment comment = new Comment(UUID.randomUUID(), experienceId, authorId, parentId, body);
        commentRepository.save(comment);

        Map<UUID, String> authorNames = loadAuthorNames(List.of(comment));
        return CommentResponse.from(
                comment, authorId, authorIsAdmin, authorNames, experience.getContributorId(), List.of());
    }

    /** Soft-deletes a comment (author or admin only). The row and its replies are kept; only
     * {@code deleted_at} is set. 204 semantics — deleting an already-deleted comment is a no-op. */
    @Transactional
    public void delete(UUID viewerId, boolean viewerIsAdmin, UUID experienceId, UUID commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found"));
        // Guard against a comment id from a different experience than the path's.
        if (!comment.getExperienceId().equals(experienceId)) {
            throw new NotFoundException("Comment not found");
        }
        boolean isAuthor = viewerId != null && viewerId.equals(comment.getAuthorId());
        if (!isAuthor && !viewerIsAdmin) {
            throw new ForbiddenException("You can't delete this comment");
        }
        if (!comment.isDeleted()) {
            comment.markDeleted();
            commentRepository.save(comment);
        }
    }

    /** Reuses getPublicView's visibility + full-access gate. Loads the experience, requires it
     * PUBLISHED (else 404, indistinguishable from not-found), then requires full access (else
     * 403). Returns the loaded experience so callers can read its contributorId. */
    private Experience requirePublishedWithFullAccess(UUID viewerId, boolean viewerIsAdmin, UUID experienceId) {
        Experience experience = experienceRepository.findById(experienceId)
                .orElseThrow(() -> new NotFoundException("Experience not found"));
        if (experience.getStatus() != ExperienceStatus.PUBLISHED) {
            throw new NotFoundException("Experience not found");
        }
        if (!hasFullAccess(viewerId, viewerIsAdmin, experience)) {
            throw new ForbiddenException("You don't have access to this experience's comments");
        }
        return experience;
    }

    /** The same "full access" predicate as ExperienceService#getPublicView, with PUBLISHED
     * already established by the caller: free OR owner OR admin OR a held entitlement. A free
     * experience is open to everyone including a guest (viewerId null). */
    private boolean hasFullAccess(UUID viewerId, boolean viewerIsAdmin, Experience experience) {
        if (experience.isFree()) {
            return true;
        }
        boolean isOwner = viewerId != null && viewerId.equals(experience.getContributorId());
        if (isOwner || viewerIsAdmin) {
            return true;
        }
        return viewerId != null
                && entitlementRepository.existsByUserIdAndExperienceId(viewerId, experience.getId());
    }

    /** Parses and validates a reply's parent. Null/blank means a top-level comment. Otherwise
     * the parent must exist, belong to this experience, be non-deleted, and itself be
     * top-level (parentId null) — enforcing the one-level-deep rule. */
    private UUID resolveParent(UUID experienceId, String rawParentId) {
        if (rawParentId == null || rawParentId.isBlank()) {
            return null;
        }
        UUID parentId;
        try {
            parentId = UUID.fromString(rawParentId.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidStateException("parentId is not a valid id");
        }
        Comment parent = commentRepository.findById(parentId)
                .orElseThrow(() -> new NotFoundException("Parent comment not found"));
        if (!parent.getExperienceId().equals(experienceId)) {
            throw new NotFoundException("Parent comment not found");
        }
        if (parent.isDeleted()) {
            throw new InvalidStateException("Can't reply to a deleted comment");
        }
        if (parent.getParentId() != null) {
            throw new InvalidStateException("Replies can only be one level deep");
        }
        return parentId;
    }

    /** Builds the nested response: top-level (parentId null) newest-first, each with its
     * replies grouped by parentId oldest-first. Deleted comments (top-level or reply) are kept
     * in place and rendered as [deleted] by {@link CommentResponse#from}. */
    private List<CommentResponse> buildTree(
            List<Comment> all, UUID viewerId, boolean viewerIsAdmin, UUID contributorId) {
        Map<UUID, String> authorNames = loadAuthorNames(all);

        List<Comment> topLevel = new ArrayList<>();
        Map<UUID, List<Comment>> repliesByParent = new HashMap<>();
        for (Comment c : all) {
            if (c.getParentId() == null) {
                topLevel.add(c);
            } else {
                repliesByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
            }
        }

        topLevel.sort(Comparator.comparing(Comment::getCreatedAt).reversed());

        List<CommentResponse> result = new ArrayList<>(topLevel.size());
        for (Comment parent : topLevel) {
            List<Comment> replies = repliesByParent.getOrDefault(parent.getId(), List.of());
            List<Comment> sortedReplies = new ArrayList<>(replies);
            sortedReplies.sort(Comparator.comparing(Comment::getCreatedAt));
            List<CommentResponse> replyNodes = new ArrayList<>(sortedReplies.size());
            for (Comment reply : sortedReplies) {
                replyNodes.add(CommentResponse.from(
                        reply, viewerId, viewerIsAdmin, authorNames, contributorId, List.of()));
            }
            result.add(CommentResponse.from(
                    parent, viewerId, viewerIsAdmin, authorNames, contributorId, replyNodes));
        }
        return result;
    }

    /** Batch-loads author display names to avoid an N+1 lookup per comment. Only non-deleted
     * comments contribute an id (a deleted comment's author is redacted from the response). */
    private Map<UUID, String> loadAuthorNames(List<Comment> comments) {
        Set<UUID> authorIds = comments.stream()
                .filter(c -> !c.isDeleted())
                .map(Comment::getAuthorId)
                .collect(Collectors.toSet());
        if (authorIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, User::getDisplayName));
    }
}
