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
  const [search, setSearch] = useState("");

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
  // Client-side is the right call here, not a server-side search like Browse's — this
  // list is already scoped to one person's own purchases (listMyPurchases returns the
  // whole thing, unpaginated), nowhere near the size of the full marketplace.
  const query = search.trim().toLowerCase();
  const filtered = query
    ? unlocked.filter(
        (p) =>
          p.company.toLowerCase().includes(query) ||
          p.roleTitle.toLowerCase().includes(query) ||
          (p.level ?? "").toLowerCase().includes(query),
      )
    : unlocked;

  return (
    <div>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "flex-end",
          marginBottom: 20,
          flexWrap: "wrap",
          gap: 14,
        }}
      >
        <h1 className="page-title">My library</h1>
        {unlocked.length > 0 && (
          <input
            placeholder="Search company, role, or level…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="text-input"
            style={{ width: 260 }}
          />
        )}
      </div>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : unlocked.length === 0 ? (
        <p className="muted">Nothing unlocked yet — experiences you unlock will show up here.</p>
      ) : filtered.length === 0 ? (
        <p className="muted">No unlocked experiences match "{search.trim()}".</p>
      ) : (
        <div className="stack-sm">
          {filtered.map((p) => (
            <div key={p.id} className="card card-pad-sm row" style={{ justifyContent: "space-between" }}>
              <div>
                <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 15 }}>
                  {p.company} — {p.roleTitle}
                  {p.level && (
                    <span style={{ fontFamily: "var(--font-body)", fontWeight: 600, color: "var(--text-muted)", fontSize: 13 }}>
                      {" "}
                      · {p.level}
                    </span>
                  )}
                </div>
                <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                  <strong className="price-tag" style={{ fontSize: 13 }}>₹{(p.amountPaise / 100).toFixed(2)}</strong>
                  {"  "}
                  unlocked {new Date(p.createdAt).toLocaleDateString()}
                </span>
              </div>
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
