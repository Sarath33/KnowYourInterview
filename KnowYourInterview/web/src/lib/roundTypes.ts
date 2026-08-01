/**
 * The round-type vocabulary, shared by everything that writes or displays a round.
 *
 * These values are the wire format (`ExperienceRound.roundType`) — the labels exist purely
 * for display. This lived inside SubmissionWorkspace.tsx until it turned out the viewer-facing
 * detail page had no access to it and was rendering the raw enum: a contributor filling in the
 * form picked "System design", and the person who paid to read it saw `SYSTEM_DESIGN`. Any new
 * surface that renders a round should import roundTypeLabel from here rather than printing
 * `round.roundType` directly.
 *
 * The backend doesn't constrain roundType to this list (it's a free-form string column), so
 * roundTypeLabel falls back to the raw value rather than rendering nothing for anything
 * unrecognised — an older row, or one written before an entry was added here, still shows up.
 */
export const ROUND_TYPES: { value: string; label: string }[] = [
  { value: "PHONE_SCREEN", label: "Phone screen" },
  { value: "ONSITE", label: "Onsite" },
  { value: "SYSTEM_DESIGN", label: "System design" },
  { value: "CODING", label: "Coding" },
  { value: "TAKE_HOME", label: "Take-home" },
  { value: "LIVE_DEBUGGING", label: "Live debugging" },
  { value: "PRODUCT_SENSE", label: "Product sense" },
  { value: "CASE_STUDY", label: "Case study" },
  { value: "LEADERSHIP", label: "Leadership / behavioral" },
  { value: "ONSITE_BAR_RAISER", label: "Bar raiser" },
];

export function roundTypeLabel(roundType: string): string {
  return ROUND_TYPES.find((t) => t.value === roundType)?.label ?? roundType;
}
