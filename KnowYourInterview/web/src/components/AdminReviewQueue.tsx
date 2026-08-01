import { useState } from "react";
import type { ExperienceRequest } from "../../../shared/types";
import * as api from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { errorMessage } from "../lib/errors";
import { CheckIcon, FileTextIcon, XIcon } from "./icons";
import { EditDetailsForm } from "./SubmissionWorkspace";

export function AdminReviewQueue() {
  const { data, loading, error: loadError, refetch } = useAsync(() => api.adminReviewQueue(), []);
  const [actionError, setActionError] = useState<string | null>(null);
  const [reasonDrafts, setReasonDrafts] = useState<Record<string, string>>({});
  const [correctionDrafts, setCorrectionDrafts] = useState<Record<string, string>>({});
  const [editingId, setEditingId] = useState<string | null>(null);

  const queue = data ?? [];
  const error = actionError ?? loadError;

  const approve = async (id: string) => {
    setActionError(null);
    try {
      await api.adminApprove(id);
      await refetch();
    } catch (err) {
      setActionError(errorMessage(err));
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
              <p style={{ fontSize: 13, color: "var(--text-secondary-2)", fontWeight: 600 }}>
                {exp.rounds.length} round(s), {exp.proofDocuments.length} proof document(s)
              </p>
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

              {editingId === exp.id && (
                <EditDetailsForm
                  experience={exp}
                  onSave={(body) => saveEdit(exp.id, body)}
                  onCancel={() => setEditingId(null)}
                />
              )}

              <div className="row" style={{ marginBottom: 12 }}>
                <button type="button" onClick={() => approve(exp.id)} className="btn btn-primary">
                  Approve &amp; publish
                  <CheckIcon />
                </button>
                <input
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
    </div>
  );
}
