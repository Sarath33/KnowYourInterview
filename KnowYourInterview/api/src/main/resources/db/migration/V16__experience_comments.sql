-- Comments on an interview-experience detail page. Access-gated exactly like the write-up
-- itself (see ExperienceService#getPublicView / CommentService): reading requires full
-- access to the experience — free+published is open to everyone including guests, a paid
-- experience only to the owner, an admin, or a viewer holding an entitlement. Posting
-- additionally requires a signed-in, email-verified account.
--
-- Threading is deliberately ONE level deep: a comment is either top-level (parent_id NULL)
-- or a direct reply to a top-level comment. The service enforces that a reply's parent is
-- itself top-level; the self-referential FK here only guarantees the parent exists and is
-- cleaned up if it's ever hard-deleted.
--
-- Deletion is SOFT: deleted_at is stamped and the row (and its replies) stay put, so a
-- deleted comment can still render as "[deleted]" while preserving the thread around it.
-- The row is never physically removed by the app.
CREATE TABLE experience_comments (
    id UUID PRIMARY KEY,
    experience_id UUID NOT NULL REFERENCES experiences(id) ON DELETE CASCADE,
    author_id UUID NOT NULL REFERENCES users(id),
    parent_id UUID REFERENCES experience_comments(id) ON DELETE CASCADE,
    body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- Listing a page's comments is always "by experience, ordered by time" — the service reads
-- the whole set for one experience and builds the newest-first / oldest-first tree in memory.
CREATE INDEX idx_experience_comments_experience_created ON experience_comments(experience_id, created_at);
-- Grouping replies under their parent, and the ON DELETE CASCADE self-reference above.
CREATE INDEX idx_experience_comments_parent ON experience_comments(parent_id);
