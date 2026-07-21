import { useEffect, useState } from "react";
import type { Payout } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong";
}

export function MyPayouts() {
  const { accessToken } = useAuth();
  const token = accessToken!;
  const [payouts, setPayouts] = useState<Payout[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    api
      .listMyPayouts(token)
      .then(setPayouts)
      .catch((err) => setError(errorMessage(err)))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (loading) return <p>Loading…</p>;

  return (
    <div style={{ marginTop: "1.5rem" }}>
      <h2>My payouts</h2>
      <p style={{ color: "#555" }}>
        Flat-fee payouts for published experiences. Paid manually for now — you'll be
        contacted for bank/UPI details when one's owed to you.
      </p>
      {error && <p style={{ color: "red" }}>{error}</p>}
      {payouts.length === 0 ? (
        <p>Nothing here yet — payouts show up once one of your experiences is published.</p>
      ) : (
        <ul>
          {payouts.map((p) => (
            <li key={p.id} style={{ marginBottom: "0.5rem" }}>
              {p.company} — {p.roleTitle}: <strong>₹{(p.amountPaise / 100).toFixed(2)}</strong> — {p.status}
              {p.paidAt && <span> (paid {new Date(p.paidAt).toLocaleDateString()})</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
