import { useState } from "react";
import type { FormEvent } from "react";
import type { RoundRequest } from "../../../../shared/types";
import { errorMessage } from "../../lib/errors";
import { ROUND_TYPES, emptyRoundForm, toRoundFormState } from "./types";
import type { RoundFormState, RoundLike } from "./types";

/** Full round-capture form (type, duration, difficulty, topics, questions, approach,
 * interviewer behavior). Reused for three cases: adding a round to an existing
 * submission, queueing a round locally on the New draft form (which doesn't have an
 * experience id yet), and editing an already-saved or already-queued round in place
 * (pass `initial` and `onCancel` for that case — otherwise it behaves as an add form).
 * onSubmit may reject (e.g. an API call failing) — the form surfaces that inline and
 * keeps the entered values so nothing is lost. */
export function AddRoundForm({
  initial,
  submitLabel,
  onSubmit,
  onCancel,
}: {
  initial?: RoundLike;
  submitLabel?: string;
  onSubmit: (round: RoundRequest) => Promise<void>;
  onCancel?: () => void;
}) {
  const [roundForm, setRoundForm] = useState<RoundFormState>(() => (initial ? toRoundFormState(initial) : emptyRoundForm));
  const [roundFormError, setRoundFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const isEditing = !!initial;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setRoundFormError(null);
    if (!roundForm.roundType) {
      setRoundFormError("Round type is required.");
      return;
    }
    setSubmitting(true);
    try {
      await onSubmit({
        roundType: roundForm.roundType,
        durationMinutes: roundForm.durationMinutes ? Number(roundForm.durationMinutes) : undefined,
        difficulty: roundForm.difficulty ? Number(roundForm.difficulty) : undefined,
        topicsTags: roundForm.topicsTags
          ? roundForm.topicsTags.split(",").map((t) => t.trim()).filter(Boolean)
          : undefined,
        questionsAsked: roundForm.questionsAsked || undefined,
        approach: roundForm.approach || undefined,
        interviewerBehavior: roundForm.interviewerBehavior || undefined,
      });
      if (!isEditing) setRoundForm(emptyRoundForm);
    } catch (err) {
      setRoundFormError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="round-card stack-md" style={{ marginTop: 16 }}>
      <div className="round-title" style={{ marginBottom: 0 }}>
        {isEditing ? "Edit round" : "Add a round"}
      </div>
      <div className="round-form-grid">
        <div className="field">
          <label className="field-label" htmlFor="rf-type">
            Round type
          </label>
          <select
            id="rf-type"
            className="select"
            value={roundForm.roundType}
            onChange={(e) => setRoundForm({ ...roundForm, roundType: e.target.value })}
          >
            <option value="">Select…</option>
            {ROUND_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label className="field-label" htmlFor="rf-duration">
            Duration (min)
          </label>
          <input
            id="rf-duration"
            type="number"
            min={0}
            className="text-input"
            value={roundForm.durationMinutes}
            onChange={(e) => setRoundForm({ ...roundForm, durationMinutes: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="rf-difficulty">
            Difficulty (1-5)
          </label>
          <input
            id="rf-difficulty"
            type="number"
            min={1}
            max={5}
            className="text-input"
            value={roundForm.difficulty}
            onChange={(e) => setRoundForm({ ...roundForm, difficulty: e.target.value })}
          />
        </div>
      </div>
      <div className="field">
        <label className="field-label" htmlFor="rf-topics">
          Topics covered (comma-separated)
        </label>
        <input
          id="rf-topics"
          className="text-input"
          placeholder="e.g. System design, Python fundamentals, AI projects"
          value={roundForm.topicsTags}
          onChange={(e) => setRoundForm({ ...roundForm, topicsTags: e.target.value })}
        />
      </div>
      <div className="field">
        <label className="field-label" htmlFor="rf-questions">
          Questions asked
        </label>
        <textarea
          id="rf-questions"
          rows={2}
          className="textarea"
          placeholder="e.g. Implement an LRU cache — SmartCache(capacity), get(key), put(key, value)"
          value={roundForm.questionsAsked}
          onChange={(e) => setRoundForm({ ...roundForm, questionsAsked: e.target.value })}
        />
      </div>
      <div className="field">
        <label className="field-label" htmlFor="rf-approach">
          Your approach
        </label>
        <textarea
          id="rf-approach"
          rows={2}
          className="textarea"
          placeholder="e.g. HashMap + doubly linked list for O(1) get/put, moved node to front on access"
          value={roundForm.approach}
          onChange={(e) => setRoundForm({ ...roundForm, approach: e.target.value })}
        />
      </div>
      <div className="field">
        <label className="field-label" htmlFor="rf-interviewer">
          Interviewer behavior / follow-ups
        </label>
        <textarea
          id="rf-interviewer"
          rows={2}
          className="textarea"
          placeholder="e.g. Asked me to dry-run test cases, justify O(1) complexity, and discuss edge cases like capacity = 1"
          value={roundForm.interviewerBehavior}
          onChange={(e) => setRoundForm({ ...roundForm, interviewerBehavior: e.target.value })}
        />
      </div>
      {roundFormError && <p className="error-text">{roundFormError}</p>}
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={submitting} style={{ alignSelf: "flex-start" }}>
          {submitting ? "Saving…" : submitLabel ?? (isEditing ? "Save changes" : "Add round")}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} className="btn btn-outline" disabled={submitting}>
            Cancel
          </button>
        )}
      </div>
    </form>
  );
}
