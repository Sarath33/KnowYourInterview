import { useEffect, useState } from "react";
import type { ExperienceTeaser } from "../../../shared/types";
import * as api from "../lib/api";

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
    <div style={{ marginTop: "1.5rem" }}>
      <h2>Browse published experiences</h2>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          load();
        }}
      >
        <input placeholder="Filter by company" value={company} onChange={(e) => setCompany(e.target.value)} />
        <button type="submit">Filter</button>
      </form>

      {error && <p style={{ color: "red" }}>{error}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : items.length === 0 ? (
        <p>Nothing published yet.</p>
      ) : (
        <ul>
          {items.map((exp) => (
            <li key={exp.id} style={{ marginBottom: "0.75rem" }}>
              <strong>
                {exp.company} — {exp.roleTitle}
              </strong>{" "}
              {exp.level && <span>({exp.level})</span>} — ₹{(exp.pricePaise / 100).toFixed(2)}
              <br />
              <span>{exp.teaser}</span>
              <br />
              <button type="button" onClick={() => onSelect(exp.id)}>
                View
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
