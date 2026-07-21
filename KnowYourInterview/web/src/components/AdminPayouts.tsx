import { useEffect, useState } from "react";
import type { PayoutAdminView } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong";
}

export function AdminPayouts() {
  const { accessToken } = useAuth();
  const token = accessToken!;
  const [queue, setQueue] = useState<PayoutAdminView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [referenceDrafts, setReferenceDrafts] = useState<Record<string, string>>({});

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setQueue(await api.adminPayoutQueue(token));
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

  const markPaid = async (id: string) => {
    setError(null);
    try {
      await api.adminMarkPayoutPaid(token, id, { reference: referenceDrafts[id]?.trim() || undefined });
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  return (
    <div style={{ marginTop: "1.5rem" }}>
      <h2>Contributor payouts</h2>
      <p style={{ color: "#555" }}>
        RazorpayX isn't wired up yet — wire the flat fee to the contributor yourself (bank
        transfer/UPI), then mark it paid here.
      </p>
      {error && <p style={{ color: "red" }}>{error}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : queue.length === 0 ? (
        <p>Nothing owed right now.</p>
      ) : (
        queue.map((payout) => (
          <div
            key={payout.id}
            style={{ border: "1px solid #ccc", padding: "1rem", marginBottom: "1rem", maxWidth: 500 }}
          >
            <h3>
              {payout.company} — {payout.roleTitle}
            </h3>
            <p>
              Owed to <strong>{payout.contributorDisplayName}</strong> ({payout.contributorEmail})
            </p>
            <p>
              <strong>₹{(payout.amountPaise / 100).toFixed(2)}</strong> — {payout.status}
            </p>
            <div style={{ marginTop: "0.5rem" }}>
              <input
                placeholder="Reference (UPI/bank txn ID, optional)"
                value={referenceDrafts[payout.id] ?? ""}
                onChange={(e) => setReferenceDrafts({ ...referenceDrafts, [payout.id]: e.target.value })}
              />
              <button type="button" onClick={() => markPaid(payout.id)}>
                Mark paid
              </button>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
