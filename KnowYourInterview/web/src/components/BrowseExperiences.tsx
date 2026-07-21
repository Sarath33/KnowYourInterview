import { useEffect, useState } from "react";
import type { ExperienceTeaser } from "../../../shared/types";
import * as api from "../lib/api";
import { OutcomeTag, RemoteTag } from "./tags";
import { ArrowRightIcon } from "./icons";

function levelLine(exp: ExperienceTeaser): string {
  return [exp.level, exp.location].filter(Boolean).join(" · ") || "—";
}

export function BrowseExperiences({ onSelect }: { onSelect: (experienceId: string) => void }) {
  const [items, setItems] = useState<ExperienceTeaser[]>([]);
  const [company, setCompany] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.browseExperiences({ company: company || undefined });
      setItems(result.items);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          alignItems: "flex-end",
          marginBottom: 28,
          flexWrap: "wrap",
          gap: 14,
        }}
      >
        <div>
          <div className="page-kicker">Marketplace</div>
          <h1 className="page-title">Browse published experiences</h1>
        </div>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            load();
          }}
          style={{ display: "flex", gap: 8 }}
        >
          <input
            placeholder="Filter by company"
            value={company}
            onChange={(e) => setCompany(e.target.value)}
            className="text-input"
            style={{ width: 220 }}
          />
          <button type="submit" className="btn btn-outline">
            Filter
          </button>
        </form>
      </div>

      {error && <p className="error-text">{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : items.length === 0 ? (
        <p className="muted">Nothing published matches that filter yet.</p>
      ) : (
        <div className="browse-grid">
          {items.map((exp) => (
            <div key={exp.id} className="card card-pad-sm browse-card">
              <div className="card-kicker">{levelLine(exp)}</div>
              <div className="card-title">
                {exp.company} — {exp.roleTitle}
              </div>
              <p style={{ margin: 0, color: "var(--text-secondary)", fontSize: 14, lineHeight: 1.5 }}>
                {exp.teaser}
              </p>
              <div className="row" style={{ gap: 8 }}>
                <OutcomeTag outcome={exp.outcome} />
                {exp.isRemote && <RemoteTag />}
              </div>
              <div className="browse-card-footer">
                <span className="price-tag">₹{(exp.pricePaise / 100).toFixed(2)}</span>
                <button type="button" onClick={() => onSelect(exp.id)} className="btn btn-outline btn-outline-accent">
                  View
                  <ArrowRightIcon />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
