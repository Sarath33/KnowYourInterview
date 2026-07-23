import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { ExperienceTeaser } from "../../../shared/types";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { OutcomeTag, RemoteTag, UnlockedTag } from "./tags";
import { ArrowRightIcon } from "./icons";
import { interviewedLabel, roundCountLabel } from "../lib/format";

const PAGE_SIZE = 20;

function levelLine(exp: ExperienceTeaser): string {
  return [exp.level, exp.location].filter(Boolean).join(" · ") || "—";
}

interface Filters {
  company: string;
  roleTitle: string;
  level: string;
  year: string;
  search: string;
}

const emptyFilters: Filters = { company: "", roleTitle: "", level: "", year: "", search: "" };

type SortOption = "newest" | "priceLow" | "priceHigh";

const SORT_LABELS: Record<SortOption, string> = {
  newest: "Newest first",
  priceLow: "Price: low to high",
  priceHigh: "Price: high to low",
};

export function BrowseExperiences({ onSelect }: { onSelect: (experienceId: string) => void }) {
  const { accessToken } = useAuth();
  const [items, setItems] = useState<ExperienceTeaser[]>([]);
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<Filters>(emptyFilters);
  const [sort, setSort] = useState<SortOption>("newest");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalItems, setTotalItems] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = async (f: Filters, p: number, s: SortOption) => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.browseExperiences(
        {
          company: f.company || undefined,
          roleTitle: f.roleTitle || undefined,
          level: f.level || undefined,
          year: f.year ? Number(f.year) : undefined,
          search: f.search || undefined,
          sort: s,
          page: p,
          size: PAGE_SIZE,
        },
        // Guests get every card back with unlocked: false; a signed-in token lets the
        // backend flag which of these results they've already paid for.
        accessToken ?? undefined,
      );
      setItems(result.items);
      setTotalPages(result.totalPages);
      setTotalItems(result.totalItems);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load(emptyFilters, 0, "newest");
  }, []);

  const handleFilter = (e: FormEvent) => {
    e.preventDefault();
    setAppliedFilters(filters);
    setPage(0);
    load(filters, 0, sort);
  };

  const handleSortChange = (next: SortOption) => {
    setSort(next);
    setPage(0);
    load(appliedFilters, 0, next);
  };

  const goToPage = (p: number) => {
    setPage(p);
    load(appliedFilters, p, sort);
  };

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
        <div>
          <div className="page-kicker">Marketplace</div>
          <h1 className="page-title">Browse published experiences</h1>
        </div>
        <div className="field" style={{ minWidth: 180 }}>
          <label className="field-label" htmlFor="sort-select">
            Sort by
          </label>
          <select
            id="sort-select"
            className="select"
            value={sort}
            onChange={(e) => handleSortChange(e.target.value as SortOption)}
          >
            {(Object.keys(SORT_LABELS) as SortOption[]).map((s) => (
              <option key={s} value={s}>
                {SORT_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
      </div>

      <form onSubmit={handleFilter} className="row" style={{ marginBottom: 24, gap: 10 }}>
        <input
          placeholder="Search company, role, or teaser…"
          value={filters.search}
          onChange={(e) => setFilters({ ...filters, search: e.target.value })}
          className="text-input"
          style={{ width: 260 }}
        />
        <input
          placeholder="Company"
          value={filters.company}
          onChange={(e) => setFilters({ ...filters, company: e.target.value })}
          className="text-input"
          style={{ width: 180 }}
        />
        <input
          placeholder="Role title"
          value={filters.roleTitle}
          onChange={(e) => setFilters({ ...filters, roleTitle: e.target.value })}
          className="text-input"
          style={{ width: 180 }}
        />
        <input
          placeholder="Level (e.g. L4)"
          value={filters.level}
          onChange={(e) => setFilters({ ...filters, level: e.target.value })}
          className="text-input"
          style={{ width: 140 }}
        />
        <input
          placeholder="Year"
          type="number"
          value={filters.year}
          onChange={(e) => setFilters({ ...filters, year: e.target.value })}
          className="text-input"
          style={{ width: 110 }}
        />
        <button type="submit" className="btn btn-outline">
          Filter
        </button>
        {(filters.company || filters.roleTitle || filters.level || filters.year || filters.search) && (
          <button
            type="button"
            className="btn-ghost"
            onClick={() => {
              setFilters(emptyFilters);
              setAppliedFilters(emptyFilters);
              setPage(0);
              load(emptyFilters, 0, sort);
            }}
          >
            Clear
          </button>
        )}
      </form>

      {error && <p className="error-text">{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : items.length === 0 ? (
        <p className="muted">Nothing published matches that filter yet.</p>
      ) : (
        <>
          <div className="browse-grid">
            {items.map((exp) => {
              const recency = interviewedLabel(exp.interviewMonth, exp.interviewYear);
              return (
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
                    <span className="tag tag-neutral">{roundCountLabel(exp.roundCount)}</span>
                    {exp.unlocked && <UnlockedTag />}
                  </div>
                  {recency && (
                    <div style={{ fontSize: 12, color: "var(--text-muted)" }}>{recency}</div>
                  )}
                  <div className="browse-card-footer">
                    <span className="price-tag">₹{(exp.pricePaise / 100).toFixed(2)}</span>
                    <button type="button" onClick={() => onSelect(exp.id)} className="btn btn-outline btn-outline-accent">
                      View
                      <ArrowRightIcon />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          {totalPages > 1 && (
            <div className="row" style={{ justifyContent: "center", marginTop: 28, gap: 14 }}>
              <button
                type="button"
                className="btn btn-outline"
                disabled={page === 0}
                onClick={() => goToPage(page - 1)}
              >
                Previous
              </button>
              <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                Page {page + 1} of {totalPages} · {totalItems} total
              </span>
              <button
                type="button"
                className="btn btn-outline"
                disabled={page + 1 >= totalPages}
                onClick={() => goToPage(page + 1)}
              >
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
