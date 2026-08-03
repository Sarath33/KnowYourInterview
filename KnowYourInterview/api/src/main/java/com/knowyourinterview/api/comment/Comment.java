package com.knowyourinterview.api.comment;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single comment on an interview-experience detail page. Maps {@code experience_comments}
 * (see V16). Threading is one level deep: {@code parentId} is null for a top-level comment
 * and the id of a top-level comment for a reply — the one-level rule is enforced in
 * {@link CommentService}, not here.
 * <p>
 * Deletion is soft: {@link #markDeleted()} stamps {@code deletedAt} and the row (and any
 * replies under it) survive, so a deleted comment can still render as {@code [deleted]}
 * while keeping its place in the thread. There is no un-delete.
 */
@Entity
@Table(name = "experience_comments")
public class Comment {

    @Id
    private UUID id;

    @Column(name = "experience_id", nullable = false)
    private UUID experienceId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    // Null for a top-level comment; the id of a top-level comment for a reply.
    @Column(name = "parent_id")
    private UUID parentId;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Non-null once soft-deleted. The row is never physically removed by the app.
    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Comment() {
        // JPA
    }

    public Comment(UUID id, UUID experienceId, UUID authorId, UUID parentId, String body) {
        this.id = id;
        this.experienceId = experienceId;
        this.authorId = authorId;
        this.parentId = parentId;
        this.body = body;
        this.createdAt = Instant.now();
    }

    /** Soft-delete: stamps {@code deletedAt}. Idempotent — re-deleting an already-deleted
     * comment leaves the original timestamp in place. */
    public void markDeleted() {
        if (this.deletedAt == null) {
            this.deletedAt = Instant.now();
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getExperienceId() {
        return experienceId;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
