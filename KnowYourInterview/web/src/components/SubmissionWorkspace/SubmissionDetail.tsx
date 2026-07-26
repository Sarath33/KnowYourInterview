import { useState } from "react";
import type { ExperienceFull, ExperienceRequest, RoundRequest } from "../../../../shared/types";
import * as api from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { formatPaise } from "../../lib/format";
import { StatusTag } from "../tags";
import { FileTextIcon } from "../icons";
import { ConfirmDialog } from "../ConfirmDialog";
import { AddRoundForm } from "./AddRoundForm";
import { RoundCard } from "./RoundCard";
import { EditDetailsForm } from "./EditDetailsForm";
import { roundTypeLabel } from "./types";

/** Which destructive action (if any) is currently awaiting confirmation. Delete-submission,
 * remove-a-saved-round, and remove-a-proof-document all used to fire their API call the
 * instant the button was clicked — this routes them through a ConfirmDialog instead. */
type PendingConfirm =
  | { kind: "deleteExperience" }
  | { kind: "deleteRound"; roundId: string; label: string }
  | { kind: "deleteProof"; proofId: string; fileName: string }
  | null;

export function SubmissionDetail({
  experience,
  onChanged,
  onDeleted,
}: {
  experience: ExperienceFull;
  onChanged: () => void;
  onDeleted: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [editingDetails, setEditingDetails] = useState(false);
  const [editingRoundId, setEditingRoundId] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  // Content (rounds, proof docs) can be edited any time before an experience is live or
  // fully withdrawn: draft, pending review, or rejected. Matches
  // ExperienceService#requireContentEditable.
  const isContentEditable =
    experience.status === "DRAFT" ||
    experience.status === "PENDING_REVIEW" ||
    experience.status === "REJECTED";
  // Submitting and deleting are narrower — draft or rejected only. Submitting while
  // already pending review doesn't make sense, and withdrawing entirely while an admin
  // may be actively reviewing it is a bigger action than a content edit. Matches
  // ExperienceService#requireDraftOrRejected.
  const isDraftOrRejected = experience.status === "DRAFT" || experience.status === "REJECTED";

  const handleAddRound = async (round: RoundRequest) => {
    await api.addRound(experience.id, round);
    onChanged();
  };

  const handleUpdateRound = async (roundId: string, round: RoundRequest) => {
    await api.updateRound(experience.id, roundId, round);
    setEditingRoundId(null);
    onChanged();
  };

  const handleDeleteRound = async (roundId: string) => {
    try {
      await api.deleteRound(experience.id, roundId);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleDeleteProof = async (proofId: string) => {
    try {
      await api.deleteProofDocument(experience.id, proofId);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleViewProof = async (proofId: string) => {
    setError(null);
    try {
      await api.openProof(experience.id, proofId);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    try {
      await api.uploadProof(experience.id, file);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      e.target.value = "";
    }
  };

  const handleSubmit = async () => {
    setError(null);
    try {
      await api.submitExperience(experience.id);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleDeleteExperience = async () => {
    setError(null);
    setDeleting(true);
    try {
      await api.deleteExperience(experience.id);
      onDeleted();
    } catch (err) {
      setError(errorMessage(err));
      setDeleting(false);
    }
  };

  const handleUnpublish = async () => {
    setError(null);
    try {
      await api.unpublishExperience(experience.id);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleUpdateDetails = async (body: ExperienceRequest) => {
    await api.updateExperience(experience.id, body);
    setEditingDetails(false);
    onChanged();
  };

  const confirmMessage = (): { title: string; message: string; confirmLabel: string; busyLabel: string } | null => {
    if (!pendingConfirm) return null;
    switch (pendingConfirm.kind) {
      case "deleteExperience":
        return {
          title: "Delete this submission?",
          message:
            "This permanently deletes the draft, all its rounds, proof documents (including the uploaded files), and its review history. This can't be undone.",
          confirmLabel: "Delete submission",
          busyLabel: "Deleting…",
        };
      case "deleteRound":
        return {
          title: "Remove this round?",
          message: `This removes "${pendingConfirm.label}" from the submission. This can't be undone.`,
          confirmLabel: "Remove round",
          busyLabel: "Removing…",
        };
      case "deleteProof":
        return {
          title: "Remove this proof document?",
          message: `This deletes "${pendingConfirm.fileName}" from the submission. This can't be undone.`,
          confirmLabel: "Remove document",
          busyLabel: "Removing…",
        };
    }
  };

  // handleDeleteRound/handleDeleteProof catch their own errors (surfaced via `error`
  // below) and never reject, so confirmBusy is always cleared and the dialog always
  // closes here. handleDeleteExperience is the one case that unmounts this component on
  // success (via onDeleted) — closing the dialog after it settles is only observable on
  // the failure path, which is exactly when it should close so the error text is visible.
  const handleConfirm = async () => {
    if (!pendingConfirm) return;
    if (pendingConfirm.kind === "deleteExperience") {
      await handleDeleteExperience();
      setPendingConfirm(null);
      return;
    }
    setConfirmBusy(true);
    if (pendingConfirm.kind === "deleteRound") {
      await handleDeleteRound(pendingConfirm.roundId);
    } else {
      await handleDeleteProof(pendingConfirm.proofId);
    }
    setConfirmBusy(false);
    setPendingConfirm(null);
  };

  const confirmDialog = confirmMessage();

  return (
    <div className="card card-pad-md">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start" }}>
        <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 22 }}>
          {experience.company} — {experience.roleTitle}
        </div>
        {isContentEditable && !editingDetails && (
          <button type="button" onClick={() => setEditingDetails(true)} className="btn btn-outline">
            Edit details
          </button>
        )}
      </div>
      <div className="row" style={{ margin: "10px 0 18px" }}>
        <StatusTag status={experience.status} />
        <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
          {experience.status === "PUBLISHED" || experience.status === "APPROVED"
            ? `${formatPaise(experience.pricePaise)} to viewers`
            : "Price is set by the platform on publish"}
        </span>
        {!!experience.publishedAt && (
          <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
            · Unlocked by {experience.unlockCount} {experience.unlockCount === 1 ? "person" : "people"}
          </span>
        )}
      </div>
      {editingDetails && (
        <EditDetailsForm
          experience={experience}
          onSave={handleUpdateDetails}
          onCancel={() => setEditingDetails(false)}
        />
      )}
      {experience.status === "REJECTED" && experience.rejectionReason && (
        <p
          style={{
            background: "var(--danger-bg)",
            color: "var(--danger-text)",
            border: "1px solid var(--danger-border)",
            borderRadius: 8,
            padding: "10px 14px",
            fontSize: 14,
          }}
        >
          <strong>Rejected:</strong> {experience.rejectionReason} You can edit and resubmit
          it below, or delete it.
        </p>
      )}
      {experience.status === "PENDING_REVIEW" && (
        <p style={{ fontSize: 13, color: "var(--text-muted)" }}>
          Awaiting admin review. You can still add or remove rounds, and upload or delete
          proof documents, while it's pending — you just can't resubmit or delete the
          submission itself until a verdict comes back.
        </p>
      )}
      {experience.status === "PUBLISHED" && (
        <p style={{ fontSize: 13, color: "var(--text-muted)" }}>
          Live for viewers.{" "}
          <button type="button" onClick={handleUnpublish} className="btn-danger-text" style={{ padding: 0 }}>
            Unpublish to edit
          </button>{" "}
          — it'll go back through review before it's live again. Anyone who already
          unlocked it keeps their access either way.
        </p>
      )}

      <div className="divider" />
      <div className="section-title" style={{ fontSize: 16 }}>
        Rounds ({experience.rounds.length})
      </div>
      <div className="stack-sm">
        {experience.rounds.map((r) =>
          editingRoundId === r.id ? (
            <AddRoundForm
              key={r.id}
              initial={r}
              onSubmit={(round) => handleUpdateRound(r.id, round)}
              onCancel={() => setEditingRoundId(null)}
            />
          ) : (
            <RoundCard
              key={r.id}
              roundNumber={r.roundNumber}
              round={r}
              onRemove={
                isContentEditable
                  ? () =>
                      setPendingConfirm({
                        kind: "deleteRound",
                        roundId: r.id,
                        label: `Round ${r.roundNumber} — ${roundTypeLabel(r.roundType)}`,
                      })
                  : undefined
              }
              onEdit={isContentEditable ? () => setEditingRoundId(r.id) : undefined}
            />
          ),
        )}
      </div>
      {isContentEditable && <AddRoundForm onSubmit={handleAddRound} />}

      <div className="divider" />
      <div className="section-title" style={{ fontSize: 16 }}>
        Proof documents ({experience.proofDocuments.length})
      </div>
      <div className="stack-sm">
        {experience.proofDocuments.map((p) => (
          <div key={p.id} className="file-row">
            <FileTextIcon />
            <span>{p.fileName}</span>
            <button type="button" onClick={() => handleViewProof(p.id)} className="btn-ghost" style={{ fontSize: 13, fontWeight: 600 }}>
              View
            </button>
            {isContentEditable && (
              <button
                type="button"
                onClick={() => setPendingConfirm({ kind: "deleteProof", proofId: p.id, fileName: p.fileName })}
                className="btn-danger-text"
              >
                Remove
              </button>
            )}
          </div>
        ))}
      </div>
      {isContentEditable && (
        <div style={{ marginTop: 12 }}>
          <input type="file" onChange={handleUpload} />
        </div>
      )}

      {error && <p className="error-text" style={{ marginTop: 12 }}>{error}</p>}
      {isDraftOrRejected && (
        <>
          <div className="divider" />
          <div className="row" style={{ justifyContent: "space-between" }}>
            <div className="row">
              <button type="button" onClick={handleSubmit} className="btn btn-primary">
                {experience.status === "REJECTED" ? "Resubmit for review" : "Submit for review"}
              </button>
              <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                Needs at least one round and one proof document.
              </span>
            </div>
            <button
              type="button"
              onClick={() => setPendingConfirm({ kind: "deleteExperience" })}
              disabled={deleting}
              className="btn btn-outline btn-outline-danger"
            >
              {deleting ? "Deleting…" : "Delete submission"}
            </button>
          </div>
        </>
      )}

      {confirmDialog && (
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          busyLabel={confirmDialog.busyLabel}
          confirming={pendingConfirm?.kind === "deleteExperience" ? deleting : confirmBusy}
          onConfirm={handleConfirm}
          onCancel={() => setPendingConfirm(null)}
        />
      )}
    </div>
  );
}
