package com.knowyourinterview.api.comment.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.knowyourinterview.api.comment.Comment;

/**
 * The {@code ExperienceComment} node the frontend is built against. Field names and shape are
 * a fixed contract — see the feature spec.
 * <p>
 * A soft-deleted comment is redacted: {@code deleted=true}, author identity and body dropped
 * ({@code authorId}/{@code authorName} null, {@code body="[deleted]"}, {@code canDelete=false},
 * {@code authorIsContributor=false}), but {@code id}/{@code parentId}/{@code createdAt} and its
 * replies are kept so the thread around it still renders.
 */
public record CommentResponse(
        UUID id,
        UUID parentId,
        UUID authorId,
        String authorName,
        String body,
        Instant createdAt,
        boolean deleted,
        boolean canDelete,
        boolean authorIsContributor,
        List<CommentResponse> replies) {

    private static final String DELETED_BODY = "[deleted]";

    /**
     * Builds the response node for {@code comment}.
     *
     * @param viewerId       the signed-in caller, or null for a guest
     * @param viewerIsAdmin  whether the caller is an admin
     * @param authorNames    author id -> display name, batch-loaded to avoid N+1
     * @param contributorId  the experience's contributor, for the {@code authorIsContributor} flag
     * @param replies        already-built reply nodes (empty for a reply or the created node)
     */
    public static CommentResponse from(
            Comment comment,
            UUID viewerId,
            boolean viewerIsAdmin,
            Map<UUID, String> authorNames,
            UUID contributorId,
            List<CommentResponse> replies) {
        if (comment.isDeleted()) {
            return new CommentResponse(
                    comment.getId(), comment.getParentId(), null, null, DELETED_BODY,
                    comment.getCreatedAt(), true, false, false, replies);
        }
        // canDelete = (viewer is the author) OR viewer is admin — and by construction we're on
        // a non-deleted comment here, matching "AND not deleted" from the contract.
        boolean canDelete = viewerIsAdmin || (viewerId != null && viewerId.equals(comment.getAuthorId()));
        boolean authorIsContributor = comment.getAuthorId().equals(contributorId);
        return new CommentResponse(
                comment.getId(), comment.getParentId(), comment.getAuthorId(),
                authorNames.get(comment.getAuthorId()), comment.getBody(),
                comment.getCreatedAt(), false, canDelete, authorIsContributor, replies);
    }
}
