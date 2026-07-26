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

export function roundCountLabel(roundCount: number): string {
  return roundCount === 1 ? "1 round" : `${roundCount} rounds`;
}

/** Formats a paise amount as rupees, e.g. 19900 -> "₹199.00". */
export function formatPaise(paise: number): string {
  return `₹${(paise / 100).toFixed(2)}`;
}

/** "L4 · Bengaluru" / "L4" / "Bengaluru" / "—" (nothing to show). */
export function levelLine(exp: { level?: string; location?: string }): string {
  return [exp.level, exp.location].filter(Boolean).join(" · ") || "—";
}
