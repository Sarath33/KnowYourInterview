import { useState } from "react";
import * as api from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { errorMessage } from "../lib/errors";
import { formatPaise } from "../lib/format";

export function AdminPayouts() {
  const { data, loading, error: loadError, refetch } = useAsync(() => api.adminPayoutQueue(), []);
  const [actionError, setActionError] = useState<string | null>(null);
  const [referenceDrafts, setReferenceDrafts] = useState<Record<string, string>>({});

  const queue = data ?? [];
  const error = actionError ?? loadError;

  const markPaid = async (id: string) => {
    setActionError(null);
    try {
      await api.adminMarkPayoutPaid(id, { reference: referenceDrafts[id]?.trim() || undefined });
      await refetch();
    } catch (err) {
      setActionError(errorMessage(err));
    }
  };

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 6 }}>
        Contributor payouts
      </h1>
      <p className="page-subtext" style={{ marginBottom: 24 }}>
        RazorpayX isn't wired up yet — wire the flat fee to the contributor yourself (bank
        transfer/UPI), then mark it paid here.
      </p>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
      ) : queue.length === 0 ? (
        <p className="muted">Nothing owed right now.</p>
      ) : (
        <div className="stack-md" style={{ gap: 20 }}>
          {queue.map((payout) => (
            <div key={payout.id} className="card card-pad-md">
              <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19 }}>
                {payout.company} — {payout.roleTitle}
              </div>
              <p style={{ fontSize: 14, color: "var(--text-secondary)", margin: "8px 0" }}>
                Owed to <strong>{payout.contributorDisplayName}</strong> ({payout.contributorEmail})
              </p>
              <p style={{ margin: "0 0 16px" }}>
                <span className="price-tag">{formatPaise(payout.amountPaise)}</span>{" "}
                <span className="muted" style={{ fontSize: 13 }}>— {payout.status}</span>
              </p>
              <div className="row">
                <input
                  placeholder="Reference (UPI/bank txn ID, optional)"
                  value={referenceDrafts[payout.id] ?? ""}
                  onChange={(e) => setReferenceDrafts({ ...referenceDrafts, [payout.id]: e.target.value })}
                  className="text-input"
                  style={{ width: 260 }}
                />
                <button type="button" onClick={() => markPaid(payout.id)} className="btn btn-primary">
                  Mark paid
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
