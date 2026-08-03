import { useId, useState } from "react";
import type { ExperienceComment } from "../../../shared/types";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { useAsync } from "../lib/useAsync";
import { errorMessage } from "../lib/errors";
import { relativeTime } from "../lib/format";
import { ConfirmDialog } from "./ConfirmDialog";

const MAX_LENGTH = 4000;

/** Total nodes in the tree (top-level + replies), used for the "Discussion (N)" header. */
function countComments(comments: ExperienceComment[]): number {
  return comments.reduce((sum, c) => sum + 1 + c.replies.length, 0);
}

/**
 * Discussion thread for an experience's detail page. Mounts inside FullExperience — i.e. only
 * for viewers who already have full access — so it never leaks comments on a locked paid
 * experience. Free experiences are readable by everyone (the GET is public), but posting always
 * needs a confirmed, signed-in viewer.
 */
export function CommentsSection({
  experienceId,
  onLoginRequired,
}: {
  experienceId: string;
  /** The experience's contributor id. Part of the section's contract; the "Author" badge is
   * driven by the backend's per-comment `authorIsContributor` flag rather than a client-side
   * comparison, so it isn't read here. */
  authorId: string;
  onLoginRequired: () => void;
}) {
  const { user, isAuthenticated } = useAuth();
  const canPost = isAuthenticated && !!user?.emailVerified;

  // Reload when the experience changes or when the viewer's identity does (logging in can
  // grant posting rights and flips per-comment canDelete). useAsync ignores stale responses.
  const { data, loading, error, refetch } = useAsync(
    () => api.listComments(experienceId),
    [experienceId, user?.id],
  );
  // The api layer is mocked to undefined in some parent tests, and the network can hand back
  // nothing — treat any missing result as an empty thread rather than throwing.
  const comments = data ?? [];
  const total = countComments(comments);

  const [replyingTo, setReplyingTo] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<ExperienceComment | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setDeleteError(null);
    setDeleting(true);
    try {
      await api.deleteComment(experienceId, pendingDelete.id);
      setPendingDelete(null);
      await refetch();
    } catch (err) {
      setDeleteError(errorMessage(err));
    } finally {
      setDeleting(false);
    }
  };

  const renderComment = (comment: ExperienceComment, isReply: boolean) => (
    <div key={comment.id} className={isReply ? "card" : "card"} style={isReply ? undefined : { padding: 16 }}>
      <div className="row" style={{ gap: 8, alignItems: "baseline", marginBottom: 6, flexWrap: "wrap" }}>
        <span
          style={{
            fontWeight: 600,
            fontSize: 14,
            color: comment.deleted ? "var(--text-muted)" : "var(--text-primary)",
          }}
        >
          {comment.deleted ? "[deleted]" : comment.authorName ?? "Unknown"}
        </span>
        {!comment.deleted && comment.authorIsContributor && (
          <span className="tag tag-neutral tag-sm">Author</span>
        )}
        {comment.createdAt && (
          <span className="muted" style={{ fontSize: 12 }}>
            {relativeTime(comment.createdAt)}
          </span>
        )}
      </div>

      {comment.deleted ? (
        <p className="muted" style={{ fontStyle: "italic", margin: 0, fontSize: 14 }}>
          [deleted]
        </p>
      ) : (
        <p style={{ margin: 0, fontSize: 14, lineHeight: 1.6, whiteSpace: "pre-wrap", color: "var(--text-secondary)" }}>
          {comment.body}
        </p>
      )}

      {(comment.canDelete || (!isReply && !comment.deleted && canPost)) && (
        <div className="row" style={{ gap: 14, marginTop: 8 }}>
          {!isReply && !comment.deleted && canPost && (
            <button
              type="button"
              className="link-button"
              style={{ fontSize: 13 }}
              onClick={() => setReplyingTo(replyingTo === comment.id ? null : comment.id)}
            >
              {replyingTo === comment.id ? "Cancel" : "Reply"}
            </button>
          )}
          {comment.canDelete && (
            <button
              type="button"
              className="link-button"
              style={{ fontSize: 13, color: "var(--danger-text)" }}
              onClick={() => {
                setDeleteError(null);
                setPendingDelete(comment);
              }}
            >
              Delete
            </button>
          )}
        </div>
      )}

      {!isReply && replyingTo === comment.id && canPost && (
        <div style={{ marginTop: 12 }}>
          <CommentComposer
            experienceId={experienceId}
            parentId={comment.id}
            submitLabel="Reply"
            placeholder="Write a reply…"
            ariaLabel="Write a reply"
            onPosted={async () => {
              setReplyingTo(null);
              await refetch();
            }}
          />
        </div>
      )}
    </div>
  );

  return (
    <div style={{ marginTop: 28 }}>
      <div className="divider" />
      <div className="section-title">Discussion ({total})</div>

      {canPost ? (
        <div style={{ marginBottom: 20 }}>
          <CommentComposer
            experienceId={experienceId}
            parentId={null}
            submitLabel="Post"
            placeholder="Share a question or a tip…"
            ariaLabel="Add a comment"
            onPosted={refetch}
          />
        </div>
      ) : isAuthenticated ? (
        <p className="muted" style={{ fontSize: 14, marginBottom: 20 }}>
          Confirm your email to comment — see the banner at the top of the page.
        </p>
      ) : (
        <p style={{ fontSize: 14, marginBottom: 20 }}>
          <button type="button" className="link-button" onClick={onLoginRequired}>
            Log in
          </button>{" "}
          to join the discussion.
        </p>
      )}

      {error && <p className="error-text" style={{ marginBottom: 12 }}>{error}</p>}
      {loading && (
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
      )}

      {!loading && !error && comments.length === 0 && (
        <p className="muted" style={{ fontSize: 14 }}>
          No comments yet — start the discussion.
        </p>
      )}

      <div className="stack-md">
        {comments.map((comment) => (
          <div key={comment.id}>
            {renderComment(comment, false)}
            {comment.replies.length > 0 && (
              <div className="stack-md" style={{ marginTop: 10, marginLeft: 24 }}>
                {comment.replies.map((reply) => renderComment(reply, true))}
              </div>
            )}
          </div>
        ))}
      </div>

      {pendingDelete && (
        <ConfirmDialog
          title="Delete comment"
          message={
            deleteError
              ? `${deleteError} — try again?`
              : "Delete this comment? This can't be undone."
          }
          confirmLabel="Delete"
          busyLabel="Deleting…"
          confirming={deleting}
          onConfirm={confirmDelete}
          onCancel={() => {
            if (!deleting) {
              setPendingDelete(null);
              setDeleteError(null);
            }
          }}
        />
      )}
    </div>
  );
}

/** Textarea + submit button shared by the top-level composer and each inline reply. Owns its
 * own draft, submitting state, and error so one composer's failure never disturbs another. */
function CommentComposer({
  experienceId,
  parentId,
  submitLabel,
  placeholder,
  ariaLabel,
  onPosted,
}: {
  experienceId: string;
  parentId: string | null;
  submitLabel: string;
  placeholder: string;
  ariaLabel: string;
  onPosted: () => void | Promise<void>;
}) {
  const [body, setBody] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fieldId = useId();

  const trimmed = body.trim();

  const submit = async () => {
    if (!trimmed || submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      await api.createComment(experienceId, { body: trimmed, parentId });
      setBody("");
      await onPosted();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="field" style={{ marginBottom: 0 }}>
      <label htmlFor={fieldId} className="field-label">
        {ariaLabel}
      </label>
      <textarea
        id={fieldId}
        className="textarea"
        rows={3}
        value={body}
        maxLength={MAX_LENGTH}
        placeholder={placeholder}
        disabled={submitting}
        onChange={(e) => setBody(e.target.value)}
      />
      <div className="row" style={{ justifyContent: "space-between", alignItems: "center", marginTop: 8 }}>
        <span className="muted" style={{ fontSize: 12 }}>
          {body.length}/{MAX_LENGTH}
        </span>
        <button
          type="button"
          className="btn btn-primary"
          disabled={submitting || !trimmed}
          onClick={submit}
        >
          {submitting ? "Posting…" : submitLabel}
        </button>
      </div>
      {error && <p className="error-text" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}
