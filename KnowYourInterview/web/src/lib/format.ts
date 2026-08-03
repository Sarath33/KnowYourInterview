const MONTH_NAMES = [
  "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];

/** "Interviewed Mar 2026" / "Interviewed 2026" (month missing) / null (nothing to show). */
export function interviewedLabel(month?: number, year?: number): string | null {
  if (!year) return null;
  if (month && month >= 1 && month <= 12) {
    return `Interviewed ${MONTH_NAMES[month - 1]} ${year}`;
  }
  return `Interviewed ${year}`;
}

/** "Posted 29 Jul 2026" / null (not published yet, or timestamp missing). Distinct from
 * interviewedLabel above — this is when the write-up went live on Browse, not when the
 * interview itself happened, so a viewer can tell a freshly submitted experience from an
 * old one even when both are for the same interview year. */
export function publishedLabel(publishedAt?: string): string | null {
  if (!publishedAt) return null;
  const date = new Date(publishedAt);
  if (Number.isNaN(date.getTime())) return null;
  return `Posted ${date.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}`;
}

export function roundCountLabel(roundCount: number): string {
  return roundCount === 1 ? "1 round" : `${roundCount} rounds`;
}

/** "1 view" / "42 views" — raw hit counter, see ExperienceTeaser.viewCount. */
export function viewCountLabel(viewCount: number): string {
  return viewCount === 1 ? "1 view" : `${viewCount} views`;
}

/** Formats a paise amount as rupees, e.g. 19900 -> "₹199.00". */
export function formatPaise(paise: number): string {
  return `₹${(paise / 100).toFixed(2)}`;
}

/** "L4 · Bengaluru" / "L4" / "Bengaluru" / "—" (nothing to show). */
export function levelLine(exp: { level?: string; location?: string }): string {
  return [exp.level, exp.location].filter(Boolean).join(" · ") || "—";
}

/** Short relative time for comment timestamps: "just now", "5m", "3h", "2d", then an
 * absolute date once it's older than a week. Falls back to the raw string if unparseable. */
export function relativeTime(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (secs < 45) return "just now";
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d`;
  return new Date(then).toLocaleDateString();
}
