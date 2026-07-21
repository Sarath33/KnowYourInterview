import { useEffect, useState } from "react";
import type { ExperienceFull } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";

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
    <div style={{ marginTop: "1.5rem" }}>
      <h2>Admin review queue</h2>
      {error && <p style={{ color: "red" }}>{error}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : queue.length === 0 ? (
        <p>Nothing pending review.</p>
      ) : (
        queue.map((exp) => (
          <div key={exp.id} style={{ border: "1px solid #ccc", padding: "1rem", marginBottom: "1rem", maxWidth: 500 }}>
            <h3>
              {exp.company} — {exp.roleTitle}
            </h3>
            <p>{exp.teaser}</p>
            <p>
              {exp.rounds.length} round(s), {exp.proofDocuments.length} proof document(s)
            </p>
            <ul>
              {exp.proofDocuments.map((p) => (
                <li key={p.id}>
                  {p.fileName}{" "}
                  <button type="button" onClick={() => viewProof(exp.id, p.id)}>
                    View
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" onClick={() => approve(exp.id)}>
              Approve &amp; publish
            </button>
            <div style={{ marginTop: "0.5rem" }}>
              <input
                placeholder="Rejection reason"
                value={reasonDrafts[exp.id] ?? ""}
                onChange={(e) => setReasonDrafts({ ...reasonDrafts, [exp.id]: e.target.value })}
              />
              <button type="button" onClick={() => reject(exp.id)}>
                Reject
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
