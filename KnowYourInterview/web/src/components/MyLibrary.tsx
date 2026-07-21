import { useEffect, useState } from "react";
import type { Purchase } from "../../../shared/types";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";

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

  if (loading) return <p>Loading…</p>;
  if (error) return <p style={{ color: "red" }}>{error}</p>;

  const unlocked = purchases.filter((p) => p.status === "PAID");

  return (
    <div style={{ marginTop: "1.5rem" }}>
      <h2>My library</h2>
      {unlocked.length === 0 ? (
        <p>Nothing unlocked yet — experiences you unlock will show up here.</p>
      ) : (
        <ul>
          {unlocked.map((p) => (
            <li key={p.id} style={{ marginBottom: "0.5rem" }}>
              ₹{(p.amountPaise / 100).toFixed(2)} — unlocked {new Date(p.createdAt).toLocaleDateString()}{" "}
              <button type="button" onClick={() => onSelect(p.experienceId)}>
                View
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
