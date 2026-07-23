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
