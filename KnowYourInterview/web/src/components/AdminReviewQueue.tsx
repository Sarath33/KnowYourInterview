import { useEffect, useState } from "react";
import type { ExperienceFull } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { CheckIcon, FileTextIcon, XIcon } from "./icons";

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong";
}

export function AdminReviewQueue() {
  const { accessToken } = useAuth();
  const token = accessToken!;
  const [queue, setQueue] = useState<ExperienceFull[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [reasonDrafts, setReasonDrafts] = useState<Record<string, string>>({});

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setQueue(await api.adminReviewQueue(token));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const approve = async (id: string) => {
    setError(null);
    try {
      await api.adminApprove(token, id);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const reject = async (id: string) => {
    const reason = reasonDrafts[id]?.trim();
    if (!reason) {
      setError("Enter a rejection reason first");
      return;
    }
    setError(null);
    try {
      await api.adminReject(token, id, { reason });
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const viewProof = async (experienceId: string, proofId: string) => {
    setError(null);
    try {
      await api.openProof(token, experienceId, proofId);
    } catch (err) {
      setError(errorMessage(err));
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
        <p className="muted">Loading…</p>
      ) : queue.length === 0 ? (
        <p className="muted">Nothing pending review.</p>
      ) : (
        <div className="stack-md" style={{ gap: 20 }}>
          {queue.map((exp) => (
            <div key={exp.id} className="card card-pad-md">
              <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19 }}>
                {exp.company} — {exp.roleTitle}
              </div>
              <p style={{ fontSize: 14, color: "var(--text-secondary)", lineHeight: 1.5, margin: "10px 0" }}>
                {exp.teaser}
              </p>
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
              <div className="row">
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
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
