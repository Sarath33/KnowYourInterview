import { useState } from "react";
import type { ExperienceFull, ExperienceRequest } from "../../../shared/types";
import * as api from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { errorMessage } from "../lib/errors";
import { formatPaise } from "../lib/format";
import { roundTypeLabel } from "../lib/roundTypes";
import { CheckIcon, FileTextIcon, XIcon } from "./icons";
import { ConfirmDialog } from "./ConfirmDialog";
import { EditDetailsForm } from "./SubmissionWorkspace";

/** Spells out what approving actually does, since it's four things at once (publish, stamp
 * publishedAt, log the review, create a payout row) behind one button — and the payout half
 * doesn't happen for a free reference submission, which is worth stating rather than leaving
 * the admin to remember. */
function approvalMessage(exp: ExperienceFull): string {
  const what = `"${exp.company} — ${exp.roleTitle}" goes live on Browse immediately`;
  return exp.isFree
    ? `${what}. It's a free reference submission, so no price is charged and no contributor payout is created. Unpublishing later is a separate action.`
    : `${what} at the platform price, and a pending contributor payout is created. Neither is reversible from here — unpublishing later pulls the listing but leaves the payout owed.`;
}

/** The top-level fields an admin needs while deciding, beyond the teaser: when the interview
 * happened, the outcome, and what the contributor said about difficulty/timeline/compensation.
 * These were all in the payload already but nothing rendered them, so the queue showed less
 * about a submission than a paying viewer sees after unlocking it. */
function ReviewFacts({ experience }: { experience: ExperienceFull }) {
  const facts: [string, string][] = [];
  if (experience.level) facts.push(["Level", experience.level]);
  if (experience.location) facts.push(["Location", experience.isRemote ? `${experience.location} (remote)` : experience.location]);
  else if (experience.isRemote) facts.push(["Location", "Remote"]);
  if (experience.interviewYear) {
    facts.push([
      "Interviewed",
      experience.interviewMonth ? `${experience.interviewMonth}/${experience.interviewYear}` : String(experience.interviewYear),
    ]);
  }
  facts.push(["Outcome", experience.outcome]);
  if (experience.overallDifficulty) facts.push(["Difficulty", `${experience.overallDifficulty}/5`]);
  if (experience.timeline) facts.push(["Timeline", experience.timeline]);
  if (experience.compensation) facts.push(["Compensation", experience.compensation]);
  facts.push(["Price on publish", experience.isFree ? "Free" : formatPaise(experience.pricePaise)]);

  return (
    <>
      <div className="detail-stat-grid" style={{ marginBottom: 18 }}>
        {facts.map(([label, value]) => (
          <div key={label}>
            <div className="detail-stat-label">{label}</div>
            <p style={{ margin: 0, fontSize: 14, color: "var(--text-secondary-2)" }}>{value}</p>
          </div>
        ))}
      </div>
      {experience.prepAdvice && (
        <>
          <div className="section-title">Prep advice</div>
          <p style={{ color: "var(--text-secondary)", fontSize: 14, lineHeight: 1.6, marginTop: 0 }}>
            {experience.prepAdvice}
          </p>
        </>
      )}
    </>
  );
}

export function AdminReviewQueue() {
  const { data, loading, error: loadError, refetch } = useAsync(() => api.adminReviewQueue(), []);
  const [actionError, setActionError] = useState<string | null>(null);
  const [reasonDrafts, setReasonDrafts] = useState<Record<string, string>>({});
  const [correctionDrafts, setCorrectionDrafts] = useState<Record<string, string>>({});
  const [editingId, setEditingId] = useState<string | null>(null);
  // Approving isn't destructive, but it is one click away from publishing someone else's
  // write-up at a real price and booking a payout liability — and there's no one-click
  // undo (unpublishing is a separate action, and the payout row survives it). Worth the
  // same confirmation step the contributor-side destructive actions already have.
  const [pendingApproval, setPendingApproval] = useState<ExperienceFull | null>(null);
  const [approving, setApproving] = useState(false);

  const queue = data ?? [];
  const error = actionError ?? loadError;

  const approve = async (id: string) => {
    setActionError(null);
    setApproving(true);
    try {
      await api.adminApprove(id);
      setPendingApproval(null);
      await refetch();
    } catch (err) {
      setActionError(errorMessage(err));
    } finally {
      setApproving(false);
    }
  };

  const reject = async (id: string) => {
    const reason = reasonDrafts[id]?.trim();
    if (!reason) {
      setActionError("Enter a rejection reason first");
      return;
    }
    setActionError(null);
    try {
      await api.adminReject(id, { reason });
      await refetch();
    } catch (err) {
      setActionError(errorMessage(err));
    }
  };

  // The softer alternative to reject — see api.adminRequestCorrection. Typically used
  // right after editing the submission in place below to fix what's wrong directly,
  // and/or to leave notes on what the contributor still needs to change themselves.
  const requestCorrection = async (id: string) => {
    const notes = correctionDrafts[id]?.trim();
    if (!notes) {
      setActionError("Enter correction notes first");
      return;
    }
    setActionError(null);
    try {
      await api.adminRequestCorrection(id, { notes });
      await refetch();
    } catch (err) {
      setActionError(errorMessage(err));
    }
  };

  const saveEdit = async (id: string, body: ExperienceRequest) => {
    await api.updateExperience(id, body);
    setEditingId(null);
    await refetch();
  };

  const viewProof = async (experienceId: string, proofId: string) => {
    setActionError(null);
    try {
      await api.openProof(experienceId, proofId);
    } catch (err) {
      setActionError(errorMessage(err));
    }
  };

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 6 }}>
        Admin review queue
      </h1>
      <p className="page-subtext" style={{ marginBottom: 24 }}>
        Approve to publish at the platform price, or reject with a reason the contributor will see.
      </p>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
      ) : queue.length === 0 ? (
        <p className="muted">Nothing pending review.</p>
      ) : (
        <div className="stack-md" style={{ gap: 20 }}>
          {queue.map((exp) => (
            <div key={exp.id} className="card card-pad-md">
              <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start" }}>
                <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19 }}>
                  {exp.company} — {exp.roleTitle}
                </div>
                {editingId !== exp.id && (
                  <button type="button" onClick={() => setEditingId(exp.id)} className="btn btn-outline">
                    Edit submission
                  </button>
                )}
              </div>
              <p style={{ fontSize: 14, color: "var(--text-secondary)", lineHeight: 1.5, margin: "10px 0" }}>
                {exp.teaser}
              </p>
              {exp.sourceUrl && (
                <p style={{ fontSize: 13, color: "var(--text-secondary-2)", margin: "0 0 10px" }}>
                  <span className="tag tag-neutral" style={{ marginRight: 8 }}>
                    Reference — free
                  </span>
                  Summarized from{" "}
                  <a href={exp.sourceUrl} target="_blank" rel="noopener noreferrer">
                    {exp.sourceName || exp.sourceUrl}
                  </a>{" "}
                  — verify the source before approving.
                </p>
              )}
              {exp.confidentialNote && (
                <p
                  style={{
                    background: "var(--warning-bg)",
                    color: "var(--warning-text)",
                    border: "1px solid var(--warning-border)",
                    borderRadius: 8,
                    padding: "10px 14px",
                    fontSize: 13,
                    margin: "0 0 10px",
                  }}
                >
                  <strong>Confidential note from the submitter (visible to admins only):</strong> {exp.confidentialNote}
                </p>
              )}
              <ReviewFacts experience={exp} />

              <div className="section-title">
                {exp.rounds.length === 1 ? "1 round" : `${exp.rounds.length} rounds`}
              </div>
              {exp.rounds.length === 0 ? (
                <p className="muted" style={{ marginTop: 0 }}>
                  No rounds — this shouldn't be submittable; check before approving.
                </p>
              ) : (
                <div className="stack-md" style={{ marginBottom: 18 }}>
                  {exp.rounds.map((round) => (
                    <div key={round.id} className="round-card">
                      <div className="round-title">
                        Round {round.roundNumber} — {roundTypeLabel(round.roundType)}
                      </div>
                      <div className="round-meta">
                        {round.durationMinutes && <span>{round.durationMinutes} min</span>}
                        {round.difficulty && <span>Difficulty {round.difficulty}/5</span>}
                      </div>
                      {round.topicsTags && round.topicsTags.length > 0 && (
                        <p className="round-field">
                          <strong>Topics:</strong> {round.topicsTags.join(", ")}
                        </p>
                      )}
                      {round.questionsAsked && (
                        <p className="round-field">
                          <strong>Questions:</strong> {round.questionsAsked}
                        </p>
                      )}
                      {round.approach && (
                        <p className="round-field">
                          <strong>Approach:</strong> {round.approach}
                        </p>
                      )}
                      {round.interviewerBehavior && (
                        <p className="round-field">
                          <strong>Interviewer:</strong> {round.interviewerBehavior}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              )}

              <div className="section-title">
                {exp.proofDocuments.length === 1 ? "1 proof document" : `${exp.proofDocuments.length} proof documents`}
              </div>
              {exp.proofDocuments.length === 0 ? (
                <p className="muted" style={{ marginTop: 0, marginBottom: 18 }}>
                  No proof documents. Free contributions publish without review and never reach this queue, so
                  something submitted here should have at least one — verify before approving.
                </p>
              ) : (
                <div className="stack-sm" style={{ marginBottom: 18 }}>
                  {exp.proofDocuments.map((p) => (
                    <div key={p.id} className="file-row">
                      <FileTextIcon />
                      <span>{p.fileName}</span>
                      <button type="button" onClick={() => viewProof(exp.id, p.id)} className="btn btn-outline" style={{ padding: "4px 10px", fontSize: 12 }}>
                        View
                      </button>
                    </div>
                  ))}
                </div>
              )}

              {editingId === exp.id && (
                <EditDetailsForm
                  experience={exp}
                  onSave={(body) => saveEdit(exp.id, body)}
                  onCancel={() => setEditingId(null)}
                />
              )}

              <div className="row" style={{ marginBottom: 12 }}>
                <button type="button" onClick={() => setPendingApproval(exp)} className="btn btn-primary">
                  Approve &amp; publish
                  <CheckIcon />
                </button>
                <input
                  aria-label={`Rejection reason for ${exp.company} — ${exp.roleTitle}`}
                  placeholder="Rejection reason"
                  value={reasonDrafts[exp.id] ?? ""}
                  onChange={(e) => setReasonDrafts({ ...reasonDrafts, [exp.id]: e.target.value })}
                  className="text-input"
                  style={{ width: 220 }}
                />
                <button type="button" onClick={() => reject(exp.id)} className="btn btn-outline btn-outline-danger">
                  Reject
                  <XIcon />
                </button>
              </div>
              <div className="row">
                <input
                  aria-label={`Correction notes for ${exp.company} — ${exp.roleTitle}`}
                  placeholder="Correction notes for the contributor"
                  value={correctionDrafts[exp.id] ?? ""}
                  onChange={(e) => setCorrectionDrafts({ ...correctionDrafts, [exp.id]: e.target.value })}
                  className="text-input"
                  style={{ width: 320 }}
                />
                <button type="button" onClick={() => requestCorrection(exp.id)} className="btn btn-outline">
                  Request correction
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {pendingApproval && (
        <ConfirmDialog
          title="Approve and publish?"
          message={approvalMessage(pendingApproval)}
          confirmLabel="Approve & publish"
          busyLabel="Publishing…"
          confirming={approving}
          tone="primary"
          onConfirm={() => approve(pendingApproval.id)}
          onCancel={() => setPendingApproval(null)}
        />
      )}
    </div>
  );
}
