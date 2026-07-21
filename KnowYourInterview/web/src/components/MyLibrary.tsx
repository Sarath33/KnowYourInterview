import { useEffect, useState } from "react";
import type { Purchase } from "../../../shared/types";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { ArrowRightIcon } from "./icons";

export function MyLibrary({ onSelect }: { onSelect: (experienceId: string) => void }) {
  const { accessToken } = useAuth();
  const [purchases, setPurchases] = useState<Purchase[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!accessToken) return;
    setLoading(true);
    api
      .listMyPurchases(accessToken)
      .then(setPurchases)
      .catch((err) => setError(err instanceof Error ? err.message : "Something went wrong"))
      .finally(() => setLoading(false));
  }, [accessToken]);

  const unlocked = purchases.filter((p) => p.status === "PAID");

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 28 }}>
        My library
      </h1>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : unlocked.length === 0 ? (
        <p className="muted">Nothing unlocked yet — experiences you unlock will show up here.</p>
      ) : (
        <div className="stack-sm">
          {unlocked.map((p) => (
            <div key={p.id} className="card card-pad-sm row" style={{ justifyContent: "space-between" }}>
              <span style={{ fontSize: 14, color: "var(--text-secondary-2)" }}>
                <strong className="price-tag">₹{(p.amountPaise / 100).toFixed(2)}</strong>
                {"  "}
                <span className="muted">unlocked {new Date(p.createdAt).toLocaleDateString()}</span>
              </span>
              <button type="button" onClick={() => onSelect(p.experienceId)} className="btn btn-outline btn-outline-accent">
                View
                <ArrowRightIcon />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
