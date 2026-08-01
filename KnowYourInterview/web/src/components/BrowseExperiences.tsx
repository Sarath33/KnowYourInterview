import { useState } from "react";
import type { FormEvent } from "react";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { useAsync } from "../lib/useAsync";
import { OutcomeTag, RemoteTag, UnlockedTag } from "./tags";
import { ArrowRightIcon } from "./icons";
import { formatPaise, interviewedLabel, levelLine, publishedLabel, roundCountLabel, viewCountLabel } from "../lib/format";

const PAGE_SIZE = 20;

type Pricing = "" | "PAID" | "FREE";

interface Filters {
  company: string;
  roleTitle: string;
  level: string;
  year: string;
  pricing: Pricing;
  search: string;
}

const emptyFilters: Filters = { company: "", roleTitle: "", level: "", year: "", pricing: "PAID", search: "" };

const PRICING_LABELS: Record<Pricing, string> = {
  "": "All",
  PAID: "Paid",
  FREE: "Free",
};

type SortOption = "newest" | "priceLow" | "priceHigh";

const SORT_LABELS: Record<SortOption, string> = {
  newest: "Newest first",
  priceLow: "Price: low to high",
  priceHigh: "Price: high to low",
};

export function BrowseExperiences({ onSelect }: { onSelect: (experienceId: string) => void }) {
  const { accessToken } = useAuth();
  const [filters, setFilters] = useState<Filters>(emptyFilters);
  const [appliedFilters, setAppliedFilters] = useState<Filters>(emptyFilters);
  const [sort, setSort] = useState<SortOption>("newest");
  const [page, setPage] = useState(0);

  // The applied filters / page / sort (and the token, so results re-flag unlocked cards on
  // sign-in) drive the fetch. useAsync tags each run so a slow earlier page can't overwrite
  // a newer one — the out-of-order race the manual load() had.
  const { data, loading, error } = useAsync(
    () =>
      api.browseExperiences({
        company: appliedFilters.company || undefined,
        roleTitle: appliedFilters.roleTitle || undefined,
        level: appliedFilters.level || undefined,
        year: appliedFilters.year ? Number(appliedFilters.year) : undefined,
        isFree: appliedFilters.pricing === "" ? undefined : appliedFilters.pricing === "FREE",
        search: appliedFilters.search || undefined,
        sort,
        page,
        size: PAGE_SIZE,
      }),
    [appliedFilters, sort, page, accessToken],
  );

  const items = data?.items ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalItems = data?.totalItems ?? 0;

  const handleFilter = (e: FormEvent) => {
    e.preventDefault();
    setAppliedFilters(filters);
    setPage(0);
  };

  const clearFilters = () => {
    setFilters(emptyFilters);
    setAppliedFilters(emptyFilters);
    setPage(0);
  };

  const hasFilters =
    filters.company ||
    filters.roleTitle ||
    filters.level ||
    filters.year ||
    filters.pricing !== emptyFilters.pricing ||
    filters.search;

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
            onChange={(e) => {
              setSort(e.target.value as SortOption);
              setPage(0);
            }}
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
          aria-label="Search company, role, or teaser"
          placeholder="Search company, role, or teaser…"
          value={filters.search}
          onChange={(e) => setFilters({ ...filters, search: e.target.value })}
          className="text-input"
          style={{ width: 260 }}
        />
        <input
          aria-label="Company"
          placeholder="Company"
          value={filters.company}
          onChange={(e) => setFilters({ ...filters, company: e.target.value })}
          className="text-input"
          style={{ width: 180 }}
        />
        <input
          aria-label="Role title"
          placeholder="Role title"
          value={filters.roleTitle}
          onChange={(e) => setFilters({ ...filters, roleTitle: e.target.value })}
          className="text-input"
          style={{ width: 180 }}
        />
        <input
          aria-label="Level (e.g. L4)"
          placeholder="Level (e.g. L4)"
          value={filters.level}
          onChange={(e) => setFilters({ ...filters, level: e.target.value })}
          className="text-input"
          style={{ width: 140 }}
        />
        <input
          aria-label="Year"
          placeholder="Year"
          type="number"
          value={filters.year}
          onChange={(e) => setFilters({ ...filters, year: e.target.value })}
          className="text-input"
          style={{ width: 110 }}
        />
        <select
          aria-label="Pricing"
          value={filters.pricing}
          onChange={(e) => {
            // Unlike the free-text inputs (which need the Filter button, since firing a
            // fetch on every keystroke would be wasteful), a dropdown pick is a single
            // deliberate action — apply it immediately, same as the sort dropdown does.
            const next = { ...filters, pricing: e.target.value as Pricing };
            setFilters(next);
            setAppliedFilters(next);
            setPage(0);
          }}
          className="select"
          style={{ width: 130 }}
        >
          {(Object.keys(PRICING_LABELS) as Pricing[]).map((p) => (
            <option key={p} value={p}>
              {PRICING_LABELS[p]}
            </option>
          ))}
        </select>
        <button type="submit" className="btn btn-primary">
          Filter
        </button>
        {hasFilters && (
          <button type="button" className="btn-ghost" onClick={clearFilters}>
            Clear
          </button>
        )}
      </form>

      {error && <p className="error-text">{error}</p>}
      {loading ? (
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
      ) : items.length === 0 ? (
        <p className="muted">Nothing published matches that filter yet.</p>
      ) : (
        <>
          <div className="browse-grid">
            {items.map((exp) => {
              const recency = interviewedLabel(exp.interviewMonth, exp.interviewYear);
              const posted = publishedLabel(exp.publishedAt);
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
                    {exp.unlocked && !exp.isFree && <UnlockedTag />}
                  </div>
                  <div style={{ fontSize: 12, color: "var(--text-muted)" }}>
                    {[recency, posted, viewCountLabel(exp.viewCount)].filter(Boolean).join(" · ")}
                  </div>
                  <div className="browse-card-footer">
                    <span className="price-tag">{exp.isFree ? "Free" : formatPaise(exp.pricePaise)}</span>
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
                onClick={() => setPage(page - 1)}
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
                onClick={() => setPage(page + 1)}
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
