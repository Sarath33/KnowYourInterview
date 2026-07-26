import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import type {
  ExperienceEditSnapshot,
  ExperienceFull,
  ExperienceOutcome,
  ExperienceRequest,
  RoundRequest,
} from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { StatusTag } from "./tags";
import { FileTextIcon, PlusIcon } from "./icons";
import { ConfirmDialog } from "./ConfirmDialog";
import { useDraftAutosave } from "../lib/useDraftAutosave";

const OUTCOMES: ExperienceOutcome[] = ["OFFER", "REJECTED", "WITHDRAWN"];

const ROUND_TYPES: { value: string; label: string }[] = [
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

function roundTypeLabel(roundType: string): string {
  return ROUND_TYPES.find((t) => t.value === roundType)?.label ?? roundType;
}

interface RoundFormState {
  roundType: string;
  durationMinutes: string;
  difficulty: string;
  topicsTags: string;
  questionsAsked: string;
  approach: string;
  interviewerBehavior: string;
}

const emptyRoundForm: RoundFormState = {
  roundType: "",
  durationMinutes: "",
  difficulty: "",
  topicsTags: "",
  questionsAsked: "",
  approach: "",
  interviewerBehavior: "",
};

const emptyForm: ExperienceRequest = {
  company: "",
  roleTitle: "",
  level: "",
  location: "",
  isRemote: false,
  outcome: "OFFER",
  teaser: "",
  prepAdvice: "",
  timeline: "",
  compensation: "",
};

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) return err.message;
  return err instanceof Error ? err.message : "Something went wrong";
}

/** Which destructive action (if any) is currently awaiting confirmation in
 * SubmissionDetail. Delete-submission, remove-a-saved-round, and remove-a-proof-document
 * all used to fire their API call the instant the button was clicked — this routes them
 * through a ConfirmDialog instead. */
type PendingConfirm =
  | { kind: "deleteExperience" }
  | { kind: "deleteRound"; roundId: string; label: string }
  | { kind: "deleteProof"; proofId: string; fileName: string }
  | null;

interface RoundLike {
  roundType: string;
  durationMinutes?: number;
  difficulty?: number;
  topicsTags?: string[];
  questionsAsked?: string;
  approach?: string;
  interviewerBehavior?: string;
}

/** Inset card for a single round — used both for a submission's already-saved rounds
 * and for rounds queued up on a not-yet-created draft. */
function RoundCard({
  roundNumber,
  round,
  onRemove,
  onEdit,
}: {
  roundNumber: number;
  round: RoundLike;
  onRemove?: () => void;
  onEdit?: () => void;
}) {
  return (
    <div className="round-card">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "nowrap" }}>
        <div className="round-title" style={{ marginBottom: 0 }}>
          Round {roundNumber} — {roundTypeLabel(round.roundType)}
        </div>
        <div className="row" style={{ gap: 12 }}>
          {onEdit && (
            <button type="button" onClick={onEdit} className="btn-ghost" style={{ fontSize: 13, fontWeight: 600 }}>
              Edit
            </button>
          )}
          {onRemove && (
            <button type="button" onClick={onRemove} className="btn-danger-text">
              Remove
            </button>
          )}
        </div>
      </div>
      {(round.durationMinutes || round.difficulty) && (
        <div className="round-meta" style={{ marginTop: 4 }}>
          {round.durationMinutes && <span>{round.durationMinutes} min</span>}
          {round.difficulty && <span>Difficulty {round.difficulty}/5</span>}
        </div>
      )}
      {round.topicsTags && round.topicsTags.length > 0 && (
        <p className="round-field">
          <strong>Topics:</strong> {round.topicsTags.join(", ")}
        </p>
      )}
      {round.questionsAsked && (
        <p className="round-field">
          <strong>Questions:</strong> {round.questionsAsked}
        </p>
      )}
      {round.approach && (
        <p className="round-field">
          <strong>Approach:</strong> {round.approach}
        </p>
      )}
      {round.interviewerBehavior && (
        <p className="round-field">
          <strong>Interviewer:</strong> {round.interviewerBehavior}
        </p>
      )}
    </div>
  );
}

function toRoundFormState(round: RoundLike): RoundFormState {
  return {
    roundType: round.roundType,
    durationMinutes: round.durationMinutes ? String(round.durationMinutes) : "",
    difficulty: round.difficulty ? String(round.difficulty) : "",
    topicsTags: round.topicsTags ? round.topicsTags.join(", ") : "",
    questionsAsked: round.questionsAsked ?? "",
    approach: round.approach ?? "",
    interviewerBehavior: round.interviewerBehavior ?? "",
  };
}

/** Full round-capture form (type, duration, difficulty, topics, questions, approach,
 * interviewer behavior). Reused for three cases: adding a round to an existing
 * submission, queueing a round locally on the New draft form (which doesn't have an
 * experience id yet), and editing an already-saved or already-queued round in place
 * (pass `initial` and `onCancel` for that case — otherwise it behaves as an add form).
 * onSubmit may reject (e.g. an API call failing) — the form surfaces that inline and
 * keeps the entered values so nothing is lost. */
function AddRoundForm({
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

interface DetailsFormState {
  company: string;
  roleTitle: string;
  level: string;
  location: string;
  isRemote: boolean;
  interviewMonth: string;
  interviewYear: string;
  outcome: ExperienceOutcome;
  teaser: string;
  prepAdvice: string;
  overallDifficulty: string;
  timeline: string;
  compensation: string;
}

function toDetailsForm(exp: ExperienceFull): DetailsFormState {
  return {
    company: exp.company,
    roleTitle: exp.roleTitle,
    level: exp.level ?? "",
    location: exp.location ?? "",
    isRemote: exp.isRemote,
    interviewMonth: exp.interviewMonth ? String(exp.interviewMonth) : "",
    interviewYear: exp.interviewYear ? String(exp.interviewYear) : "",
    outcome: exp.outcome,
    teaser: exp.teaser,
    prepAdvice: exp.prepAdvice ?? "",
    overallDifficulty: exp.overallDifficulty ? String(exp.overallDifficulty) : "",
    timeline: exp.timeline ?? "",
    compensation: exp.compensation ?? "",
  };
}

function toExperienceRequest(f: DetailsFormState): ExperienceRequest {
  return {
    company: f.company,
    roleTitle: f.roleTitle,
    level: f.level || undefined,
    location: f.location || undefined,
    isRemote: f.isRemote,
    interviewMonth: f.interviewMonth ? Number(f.interviewMonth) : undefined,
    interviewYear: f.interviewYear ? Number(f.interviewYear) : undefined,
    outcome: f.outcome,
    teaser: f.teaser,
    prepAdvice: f.prepAdvice || undefined,
    overallDifficulty: f.overallDifficulty ? Number(f.overallDifficulty) : undefined,
    timeline: f.timeline || undefined,
    compensation: f.compensation || undefined,
  };
}

/** Edits every top-level field on an existing draft/pending/rejected submission —
 * company, role, level, location, remote, interview month/year, outcome, teaser, prep
 * advice, overall difficulty, timeline, compensation. Wired to the same
 * api.updateExperience/ExperienceService#updateDraft the create flow already used —
 * this was previously a backend-only capability with no way to reach it from the UI. */
function EditDetailsForm({
  experience,
  onSave,
  onCancel,
}: {
  experience: ExperienceFull;
  onSave: (body: ExperienceRequest) => Promise<void>;
  onCancel: () => void;
}) {
  const autosave = useDraftAutosave<DetailsFormState>(`kyi:draft:edit:${experience.id}`);
  const [form, setForm] = useState<DetailsFormState>(() => autosave.restored?.value ?? toDetailsForm(experience));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [showRestoredNotice, setShowRestoredNotice] = useState(!!autosave.restored);

  useEffect(() => {
    autosave.save(form);
    // autosave.save is stable (useCallback keyed on the autosave key, which doesn't
    // change across this component's lifetime) — only `form` should retrigger this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form]);

  const discardRestored = () => {
    autosave.clear();
    setForm(toDetailsForm(experience));
    setShowRestoredNotice(false);
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    if (!form.company.trim() || !form.roleTitle.trim() || !form.teaser.trim()) {
      setError("Company, role, and teaser are required.");
      return;
    }
    setSaving(true);
    try {
      await onSave(toExperienceRequest(form));
      autosave.clear();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="card card-pad-md stack-md" style={{ marginBottom: 20, background: "var(--bg)" }}>
      <div className="round-title" style={{ marginBottom: 0 }}>
        Edit details
      </div>
      {showRestoredNotice && (
        <p style={{ fontSize: 13, color: "var(--text-muted)", margin: 0 }}>
          Restored unsaved edits from {new Date(autosave.restored!.savedAt).toLocaleString()} —{" "}
          <button type="button" onClick={discardRestored} className="btn-ghost" style={{ padding: 0, fontSize: 13, fontWeight: 600 }}>
            Discard and use the saved version
          </button>
        </p>
      )}
      <div className="form-grid-2">
        <div className="field">
          <label className="field-label" htmlFor="ed-company">
            Company
          </label>
          <input
            id="ed-company"
            className="text-input"
            value={form.company}
            onChange={(e) => setForm({ ...form, company: e.target.value })}
            required
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-role">
            Role / title
          </label>
          <input
            id="ed-role"
            className="text-input"
            value={form.roleTitle}
            onChange={(e) => setForm({ ...form, roleTitle: e.target.value })}
            required
          />
        </div>
      </div>
      <div className="form-grid-2">
        <div className="field">
          <label className="field-label" htmlFor="ed-level">
            Level
          </label>
          <input
            id="ed-level"
            placeholder="e.g. L4, Senior"
            className="text-input"
            value={form.level}
            onChange={(e) => setForm({ ...form, level: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-location">
            Location
          </label>
          <input
            id="ed-location"
            className="text-input"
            value={form.location}
            onChange={(e) => setForm({ ...form, location: e.target.value })}
          />
        </div>
      </div>
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={form.isRemote}
          onChange={(e) => setForm({ ...form, isRemote: e.target.checked })}
        />
        Remote
      </label>
      <div className="form-grid-3">
        <div className="field">
          <label className="field-label" htmlFor="ed-month">
            Interview month
          </label>
          <input
            id="ed-month"
            type="number"
            min={1}
            max={12}
            className="text-input"
            value={form.interviewMonth}
            onChange={(e) => setForm({ ...form, interviewMonth: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-year">
            Interview year
          </label>
          <input
            id="ed-year"
            type="number"
            min={2000}
            className="text-input"
            value={form.interviewYear}
            onChange={(e) => setForm({ ...form, interviewYear: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-outcome">
            Outcome
          </label>
          <select
            id="ed-outcome"
            className="select"
            value={form.outcome}
            onChange={(e) => setForm({ ...form, outcome: e.target.value as ExperienceOutcome })}
          >
            {OUTCOMES.map((o) => (
              <option key={o} value={o}>
                {o.charAt(0) + o.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="field">
        <label className="field-label" htmlFor="ed-teaser">
          Teaser — 1-2 public sentences, no question specifics
        </label>
        <textarea
          id="ed-teaser"
          rows={3}
          className="textarea"
          value={form.teaser}
          onChange={(e) => setForm({ ...form, teaser: e.target.value })}
          required
        />
      </div>
      <div className="field">
        <label className="field-label" htmlFor="ed-prep">
          Prep advice
        </label>
        <textarea
          id="ed-prep"
          rows={3}
          className="textarea"
          value={form.prepAdvice}
          onChange={(e) => setForm({ ...form, prepAdvice: e.target.value })}
        />
      </div>
      <div className="form-grid-3">
        <div className="field">
          <label className="field-label" htmlFor="ed-difficulty">
            Overall difficulty (1-5)
          </label>
          <input
            id="ed-difficulty"
            type="number"
            min={1}
            max={5}
            className="text-input"
            value={form.overallDifficulty}
            onChange={(e) => setForm({ ...form, overallDifficulty: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-timeline">
            Timeline
          </label>
          <input
            id="ed-timeline"
            placeholder="e.g. Applied to offer in 3 weeks"
            className="text-input"
            value={form.timeline}
            onChange={(e) => setForm({ ...form, timeline: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor="ed-compensation">
            Compensation
          </label>
          <input
            id="ed-compensation"
            placeholder="e.g. 35 LPA"
            className="text-input"
            value={form.compensation}
            onChange={(e) => setForm({ ...form, compensation: e.target.value })}
          />
        </div>
      </div>
      {error && <p className="error-text">{error}</p>}
      <div className="row">
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? "Saving…" : "Save changes"}
        </button>
        <button type="button" onClick={onCancel} className="btn btn-outline" disabled={saving}>
          Cancel
        </button>
      </div>
    </form>
  );
}

/** One entry in a submission's edit history — a snapshot of the top-level fields as they
 * stood right before an edit overwrote them, plus which fields that edit changed
 * (computed server-side, see ExperienceService#listEditHistory). Collapsed by default to
 * a timestamp + changed-field tags; "View details" expands the full snapshot, most useful
 * right after a rejection to see exactly what was different before resubmitting. */
function EditHistoryEntry({ snapshot }: { snapshot: ExperienceEditSnapshot }) {
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="round-card">
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "nowrap" }}>
        <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
          {new Date(snapshot.recordedAt).toLocaleString()}
        </span>
        <button type="button" onClick={() => setExpanded(!expanded)} className="btn-ghost" style={{ fontSize: 13, fontWeight: 600 }}>
          {expanded ? "Hide details" : "View details"}
        </button>
      </div>
      <div className="row" style={{ gap: 6, marginTop: 8, flexWrap: "wrap" }}>
        {snapshot.changedFields.length === 0 ? (
          <span className="tag tag-neutral">No fields changed</span>
        ) : (
          snapshot.changedFields.map((field) => (
            <span key={field} className="tag tag-neutral">
              {field}
            </span>
          ))
        )}
      </div>
      {expanded && (
        <div className="stack-sm" style={{ marginTop: 12 }}>
          <p className="round-field">
            <strong>Company:</strong> {snapshot.company}
          </p>
          <p className="round-field">
            <strong>Role:</strong> {snapshot.roleTitle}
          </p>
          {snapshot.level && (
            <p className="round-field">
              <strong>Level:</strong> {snapshot.level}
            </p>
          )}
          {snapshot.location && (
            <p className="round-field">
              <strong>Location:</strong> {snapshot.location}
            </p>
          )}
          <p className="round-field">
            <strong>Remote:</strong> {snapshot.isRemote ? "Yes" : "No"}
          </p>
          <p className="round-field">
            <strong>Outcome:</strong> {snapshot.outcome.charAt(0) + snapshot.outcome.slice(1).toLowerCase()}
          </p>
          <p className="round-field">
            <strong>Teaser:</strong> {snapshot.teaser}
          </p>
          {snapshot.prepAdvice && (
            <p className="round-field">
              <strong>Prep advice:</strong> {snapshot.prepAdvice}
            </p>
          )}
          {snapshot.overallDifficulty && (
            <p className="round-field">
              <strong>Overall difficulty:</strong> {snapshot.overallDifficulty}/5
            </p>
          )}
          {snapshot.timeline && (
            <p className="round-field">
              <strong>Timeline:</strong> {snapshot.timeline}
            </p>
          )}
          {snapshot.compensation && (
            <p className="round-field">
              <strong>Compensation:</strong> {snapshot.compensation}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

export function SubmissionWorkspace() {
  const { accessToken } = useAuth();
  const [experiences, setExperiences] = useState<ExperienceFull[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<ExperienceRequest>(emptyForm);
  const [pendingRounds, setPendingRounds] = useState<RoundRequest[]>([]);
  const [editingPendingIndex, setEditingPendingIndex] = useState<number | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const newDraftAutosave = useDraftAutosave<{ form: ExperienceRequest; pendingRounds: RoundRequest[] }>(
    "kyi:draft:new-submission",
  );
  const [showRestoredNotice, setShowRestoredNotice] = useState(false);

  const token = accessToken!;

  // Only while actually composing a new draft — otherwise this would keep overwriting
  // the saved draft with emptyForm/[] every time creating is false (e.g. while a
  // different submission is selected in the rail).
  useEffect(() => {
    if (!creating) return;
    newDraftAutosave.save({ form, pendingRounds });
    // newDraftAutosave.save is stable (useCallback keyed on its fixed key) — only these
    // three should retrigger the save.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [creating, form, pendingRounds]);

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const mine = await api.listMyExperiences(token);
      setExperiences(mine);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Not a <form onSubmit> — the rounds section below embeds AddRoundForm, which is
  // itself a <form>, and nested <form> elements aren't valid HTML. "Create draft" is a
  // plain button that calls this directly instead.
  const handleCreate = async () => {
    setError(null);
    if (!form.company.trim() || !form.roleTitle.trim() || !form.teaser.trim()) {
      setError("Company, role, and teaser are required.");
      return;
    }
    try {
      const created = await api.createExperience(token, form);
      // The draft exists now — add any rounds queued up before creation. If one fails,
      // the draft itself is safe (already saved); surface the error after reloading and
      // still land on the draft so the rest can be added there directly.
      let roundError: string | null = null;
      for (const round of pendingRounds) {
        try {
          await api.addRound(token, created.id, round);
        } catch (err) {
          roundError = `Draft created, but adding a round failed: ${errorMessage(err)}. You can add the rest from the draft.`;
          break;
        }
      }
      setForm(emptyForm);
      setPendingRounds([]);
      setCreating(false);
      newDraftAutosave.clear();
      await load();
      setSelectedId(created.id);
      if (roundError) setError(roundError);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const selected = experiences.find((e) => e.id === selectedId) ?? null;

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 28 }}>
        My submissions
      </h1>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted">Loading…</p>
      ) : (
        <div className="submission-layout">
          <div className="submission-rail">
            {experiences.map((exp) => (
              <button
                key={exp.id}
                type="button"
                onClick={() => {
                  setSelectedId(exp.id);
                  setCreating(false);
                }}
                className={`submission-item${exp.id === selectedId ? " is-selected" : ""}`}
              >
                <div className="submission-item-title">
                  {exp.company} — {exp.roleTitle}
                </div>
                <StatusTag status={exp.status} small />
              </button>
            ))}
            <button
              type="button"
              onClick={() => {
                setCreating(true);
                setSelectedId(null);
                setEditingPendingIndex(null);
                if (newDraftAutosave.restored) {
                  setForm(newDraftAutosave.restored.value.form);
                  setPendingRounds(newDraftAutosave.restored.value.pendingRounds);
                  setShowRestoredNotice(true);
                } else {
                  setForm(emptyForm);
                  setPendingRounds([]);
                }
              }}
              className="btn-dashed"
            >
              New draft
              {newDraftAutosave.restored && (
                <span className="tag tag-neutral" style={{ fontSize: 11 }}>
                  Unsaved draft
                </span>
              )}
              <PlusIcon />
            </button>
          </div>

          <div>
            {creating && (
              <div className="card card-pad-md">
                <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19, marginBottom: 4 }}>
                  New draft
                </div>
                <p style={{ fontSize: 13, color: "var(--text-muted)", marginTop: 0, marginBottom: 18 }}>
                  The platform sets the price once it's published — you don't.
                </p>
                {showRestoredNotice && newDraftAutosave.restored && (
                  <p style={{ fontSize: 13, color: "var(--text-muted)", marginTop: -10, marginBottom: 18 }}>
                    Restored an unsaved draft from {new Date(newDraftAutosave.restored.savedAt).toLocaleString()} —{" "}
                    <button
                      type="button"
                      onClick={() => {
                        newDraftAutosave.clear();
                        setForm(emptyForm);
                        setPendingRounds([]);
                        setShowRestoredNotice(false);
                      }}
                      className="btn-ghost"
                      style={{ padding: 0, fontSize: 13, fontWeight: 600 }}
                    >
                      Discard it and start blank
                    </button>
                  </p>
                )}
                <div className="stack-md">
                  <div className="form-grid-2">
                    <div className="field">
                      <label className="field-label" htmlFor="nd-company">
                        Company
                      </label>
                      <input
                        id="nd-company"
                        className="text-input"
                        value={form.company}
                        onChange={(e) => setForm({ ...form, company: e.target.value })}
                        required
                      />
                    </div>
                    <div className="field">
                      <label className="field-label" htmlFor="nd-role">
                        Role / title
                      </label>
                      <input
                        id="nd-role"
                        className="text-input"
                        value={form.roleTitle}
                        onChange={(e) => setForm({ ...form, roleTitle: e.target.value })}
                        required
                      />
                    </div>
                    <div className="field">
                      <label className="field-label" htmlFor="nd-level">
                        Level
                      </label>
                      <input
                        id="nd-level"
                        placeholder="e.g. L4, Senior"
                        className="text-input"
                        value={form.level}
                        onChange={(e) => setForm({ ...form, level: e.target.value })}
                      />
                    </div>
                    <div className="field">
                      <label className="field-label" htmlFor="nd-location">
                        Location
                      </label>
                      <input
                        id="nd-location"
                        className="text-input"
                        value={form.location}
                        onChange={(e) => setForm({ ...form, location: e.target.value })}
                      />
                    </div>
                  </div>
                  <label className="checkbox-field">
                    <input
                      type="checkbox"
                      checked={form.isRemote}
                      onChange={(e) => setForm({ ...form, isRemote: e.target.checked })}
                    />
                    Remote
                  </label>
                  <div className="field">
                    <label className="field-label" htmlFor="nd-outcome">
                      Outcome
                    </label>
                    <select
                      id="nd-outcome"
                      className="select"
                      value={form.outcome}
                      onChange={(e) => setForm({ ...form, outcome: e.target.value as ExperienceOutcome })}
                    >
                      {OUTCOMES.map((o) => (
                        <option key={o} value={o}>
                          {o.charAt(0) + o.slice(1).toLowerCase()}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div className="field">
                    <label className="field-label" htmlFor="nd-teaser">
                      Teaser — 1-2 public sentences, no question specifics
                    </label>
                    <textarea
                      id="nd-teaser"
                      rows={3}
                      className="textarea"
                      value={form.teaser}
                      onChange={(e) => setForm({ ...form, teaser: e.target.value })}
                      required
                    />
                  </div>
                  <div className="divider" />
                  <div className="section-title" style={{ fontSize: 16 }}>
                    Rounds ({pendingRounds.length})
                  </div>
                  <p style={{ fontSize: 13, color: "var(--text-muted)", marginTop: -4 }}>
                    Optional here — you can also add these after creating the draft.
                  </p>
                  <div className="stack-sm">
                    {pendingRounds.map((round, i) =>
                      editingPendingIndex === i ? (
                        <AddRoundForm
                          key={i}
                          initial={round}
                          onSubmit={async (updated) => {
                            setPendingRounds(pendingRounds.map((r, j) => (j === i ? updated : r)));
                            setEditingPendingIndex(null);
                          }}
                          onCancel={() => setEditingPendingIndex(null)}
                        />
                      ) : (
                        <RoundCard
                          key={i}
                          roundNumber={i + 1}
                          round={round}
                          onRemove={() => setPendingRounds(pendingRounds.filter((_, j) => j !== i))}
                          onEdit={() => setEditingPendingIndex(i)}
                        />
                      ),
                    )}
                  </div>
                  <AddRoundForm
                    onSubmit={async (round) => {
                      setPendingRounds([...pendingRounds, round]);
                    }}
                  />

                  <div className="divider" />
                  <div className="row">
                    <button type="button" onClick={handleCreate} className="btn btn-primary">
                      Create draft
                    </button>
                    <button type="button" onClick={() => setCreating(false)} className="btn btn-outline">
                      Cancel
                    </button>
                  </div>
                </div>
              </div>
            )}

            {selected && !creating && (
              <SubmissionDetail
                // Forces a clean remount when switching between submissions in the rail
                // — without this, SubmissionDetail's local state (editingDetails,
                // editingRoundId, the new edit-history panel, etc.) would carry over from
                // whichever submission was previously selected instead of resetting.
                key={selected.id}
                experience={selected}
                token={token}
                onChanged={load}
                onDeleted={() => {
                  setSelectedId(null);
                  load();
                }}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function SubmissionDetail({
  experience,
  token,
  onChanged,
  onDeleted,
}: {
  experience: ExperienceFull;
  token: string;
  onChanged: () => void;
  onDeleted: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [editingDetails, setEditingDetails] = useState(false);
  const [editingRoundId, setEditingRoundId] = useState<string | null>(null);
  const [pendingConfirm, setPendingConfirm] = useState<PendingConfirm>(null);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<ExperienceEditSnapshot[] | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState<string | null>(null);
  // Content (rounds, proof docs) can be edited any time before an experience is live or
  // fully withdrawn: draft, pending review, or rejected. Matches
  // ExperienceService#requireContentEditable.
  const isContentEditable =
    experience.status === "DRAFT" ||
    experience.status === "PENDING_REVIEW" ||
    experience.status === "REJECTED";
  // Submitting is narrower — draft or rejected only, since there's nothing to (re)submit
  // while already pending review. Matches ExperienceService#requireDraftOrRejected.
  // Deleting isn't narrower any more — it shares isContentEditable's window below, so a
  // contributor can withdraw a submission before an admin has acted on it too.
  const isDraftOrRejected = experience.status === "DRAFT" || experience.status === "REJECTED";

  const handleAddRound = async (round: RoundRequest) => {
    await api.addRound(token, experience.id, round);
    onChanged();
  };

  const handleUpdateRound = async (roundId: string, round: RoundRequest) => {
    await api.updateRound(token, experience.id, roundId, round);
    setEditingRoundId(null);
    onChanged();
  };

  const handleDeleteRound = async (roundId: string) => {
    try {
      await api.deleteRound(token, experience.id, roundId);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleDeleteProof = async (proofId: string) => {
    try {
      await api.deleteProofDocument(token, experience.id, proofId);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleViewProof = async (proofId: string) => {
    setError(null);
    try {
      await api.openProof(token, experience.id, proofId);
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    try {
      await api.uploadProof(token, experience.id, file);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      e.target.value = "";
    }
  };

  const handleSubmit = async () => {
    setError(null);
    try {
      await api.submitExperience(token, experience.id);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleDeleteExperience = async () => {
    setError(null);
    setDeleting(true);
    try {
      await api.deleteExperience(token, experience.id);
      onDeleted();
    } catch (err) {
      setError(errorMessage(err));
      setDeleting(false);
    }
  };

  const handleUnpublish = async () => {
    setError(null);
    try {
      await api.unpublishExperience(token, experience.id);
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
  };

  const handleUpdateDetails = async (body: ExperienceRequest) => {
    await api.updateExperience(token, experience.id, body);
    setEditingDetails(false);
    onChanged();
  };

  // Lazy-loaded on first open, not fetched eagerly with the rest of the page — most
  // visits to a submission never open it, so there's no reason to pay for the extra
  // request every time.
  const toggleHistory = async () => {
    const next = !historyOpen;
    setHistoryOpen(next);
    if (next && history === null) {
      setHistoryLoading(true);
      setHistoryError(null);
      try {
        setHistory(await api.getEditHistory(token, experience.id));
      } catch (err) {
        setHistoryError(errorMessage(err));
      } finally {
        setHistoryLoading(false);
      }
    }
  };

  const confirmMessage = (): { title: string; message: string; confirmLabel: string; busyLabel: string } | null => {
    if (!pendingConfirm) return null;
    switch (pendingConfirm.kind) {
      case "deleteExperience":
        return {
          title: "Delete this submission?",
          message:
            "This permanently deletes the draft, all its rounds, proof documents (including the uploaded files), and its review history. This can't be undone.",
          confirmLabel: "Delete submission",
          busyLabel: "Deleting…",
        };
      case "deleteRound":
        return {
          title: "Remove this round?",
          message: `This removes "${pendingConfirm.label}" from the submission. This can't be undone.`,
          confirmLabel: "Remove round",
          busyLabel: "Removing…",
        };
      case "deleteProof":
        return {
          title: "Remove this proof document?",
          message: `This deletes "${pendingConfirm.fileName}" from the submission. This can't be undone.`,
          confirmLabel: "Remove document",
          busyLabel: "Removing…",
        };
    }
  };

  // handleDeleteRound/handleDeleteProof catch their own errors (surfaced via `error`
  // below) and never reject, so confirmBusy is always cleared and the dialog always
  // closes here. handleDeleteExperience is the one case that unmounts this component on
  // success (via onDeleted) — closing the dialog after it settles is only observable on
  // the failure path, which is exactly when it should close so the error text is visible.
  const handleConfirm = async () => {
    if (!pendingConfirm) return;
    if (pendingConfirm.kind === "deleteExperience") {
      await handleDeleteExperience();
      setPendingConfirm(null);
      return;
    }
    setConfirmBusy(true);
    if (pendingConfirm.kind === "deleteRound") {
      await handleDeleteRound(pendingConfirm.roundId);
    } else {
      await handleDeleteProof(pendingConfirm.proofId);
    }
    setConfirmBusy(false);
    setPendingConfirm(null);
  };

  const confirmDialog = confirmMessage();

  return (
    <div className="card card-pad-md">
      <div className="row" style={{ justifyContent: "space-between", alignItems: "flex-start" }}>
        <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 22 }}>
          {experience.company} — {experience.roleTitle}
        </div>
        <div className="row" style={{ gap: 8 }}>
          <button type="button" onClick={toggleHistory} className="btn-ghost" style={{ fontSize: 13, fontWeight: 600 }}>
            {historyOpen ? "Hide edit history" : "View edit history"}
          </button>
          {isContentEditable && !editingDetails && (
            <button type="button" onClick={() => setEditingDetails(true)} className="btn btn-outline">
              Edit details
            </button>
          )}
        </div>
      </div>
      <div className="row" style={{ margin: "10px 0 18px" }}>
        <StatusTag status={experience.status} />
        <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
          {experience.status === "PUBLISHED" || experience.status === "APPROVED"
            ? `₹${(experience.pricePaise / 100).toFixed(2)} to viewers`
            : "Price is set by the platform on publish"}
        </span>
        {!!experience.publishedAt && (
          <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
            · Unlocked by {experience.unlockCount} {experience.unlockCount === 1 ? "person" : "people"}
          </span>
        )}
      </div>
      {historyOpen && (
        <div className="stack-sm" style={{ marginBottom: 20 }}>
          {historyLoading && <p className="muted">Loading…</p>}
          {historyError && <p className="error-text">{historyError}</p>}
          {history && history.length === 0 && (
            <p className="muted">No edits recorded yet — history starts once details are changed at least once.</p>
          )}
          {history?.map((snapshot) => <EditHistoryEntry key={snapshot.id} snapshot={snapshot} />)}
        </div>
      )}
      {editingDetails && (
        <EditDetailsForm
          experience={experience}
          onSave={handleUpdateDetails}
          onCancel={() => setEditingDetails(false)}
        />
      )}
      {experience.status === "REJECTED" && experience.rejectionReason && (
        <p
          style={{
            background: "var(--danger-bg)",
            color: "var(--danger-text)",
            border: "1px solid var(--danger-border)",
            borderRadius: 8,
            padding: "10px 14px",
            fontSize: 14,
          }}
        >
          <strong>Rejected:</strong> {experience.rejectionReason} You can edit and resubmit
          it below, or delete it.
        </p>
      )}
      {experience.status === "PENDING_REVIEW" && (
        <p style={{ fontSize: 13, color: "var(--text-muted)" }}>
          Awaiting admin review. You can still add or remove rounds, upload or delete
          proof documents, or withdraw the whole submission, while it's pending — you just
          can't resubmit again until a verdict comes back.
        </p>
      )}
      {experience.status === "PUBLISHED" && (
        <p style={{ fontSize: 13, color: "var(--text-muted)" }}>
          Live for viewers.{" "}
          <button type="button" onClick={handleUnpublish} className="btn-danger-text" style={{ padding: 0 }}>
            Unpublish to edit
          </button>{" "}
          — it'll go back through review before it's live again. Anyone who already
          unlocked it keeps their access either way.
        </p>
      )}

      <div className="divider" />
      <div className="section-title" style={{ fontSize: 16 }}>
        Rounds ({experience.rounds.length})
      </div>
      <div className="stack-sm">
        {experience.rounds.map((r) =>
          editingRoundId === r.id ? (
            <AddRoundForm
              key={r.id}
              initial={r}
              onSubmit={(round) => handleUpdateRound(r.id, round)}
              onCancel={() => setEditingRoundId(null)}
            />
          ) : (
            <RoundCard
              key={r.id}
              roundNumber={r.roundNumber}
              round={r}
              onRemove={
                isContentEditable
                  ? () =>
                      setPendingConfirm({
                        kind: "deleteRound",
                        roundId: r.id,
                        label: `Round ${r.roundNumber} — ${roundTypeLabel(r.roundType)}`,
                      })
                  : undefined
              }
              onEdit={isContentEditable ? () => setEditingRoundId(r.id) : undefined}
            />
          ),
        )}
      </div>
      {isContentEditable && <AddRoundForm onSubmit={handleAddRound} />}

      <div className="divider" />
      <div className="section-title" style={{ fontSize: 16 }}>
        Proof documents ({experience.proofDocuments.length})
      </div>
      <div className="stack-sm">
        {experience.proofDocuments.map((p) => (
          <div key={p.id} className="file-row">
            <FileTextIcon />
            <span>{p.fileName}</span>
            <button type="button" onClick={() => handleViewProof(p.id)} className="btn-ghost" style={{ fontSize: 13, fontWeight: 600 }}>
              View
            </button>
            {isContentEditable && (
              <button
                type="button"
                onClick={() => setPendingConfirm({ kind: "deleteProof", proofId: p.id, fileName: p.fileName })}
                className="btn-danger-text"
              >
                Remove
              </button>
            )}
          </div>
        ))}
      </div>
      {isContentEditable && (
        <div style={{ marginTop: 12 }}>
          <input type="file" onChange={handleUpload} />
        </div>
      )}

      {error && <p className="error-text" style={{ marginTop: 12 }}>{error}</p>}
      {isContentEditable && (
        <>
          <div className="divider" />
          <div className="row" style={{ justifyContent: "space-between" }}>
            {isDraftOrRejected ? (
              <div className="row">
                <button type="button" onClick={handleSubmit} className="btn btn-primary">
                  {experience.status === "REJECTED" ? "Resubmit for review" : "Submit for review"}
                </button>
                <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                  Needs at least one round and one proof document.
                </span>
              </div>
            ) : (
              // PENDING_REVIEW: nothing to (re)submit, but withdrawing is still on the
              // table — see isContentEditable's note above handleAddRound.
              <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                Withdrawing pulls it out of the admin's review queue — this can't be undone.
              </span>
            )}
            <button
              type="button"
              onClick={() => setPendingConfirm({ kind: "deleteExperience" })}
              disabled={deleting}
              className="btn btn-outline btn-outline-danger"
            >
              {deleting ? "Deleting…" : "Delete submission"}
            </button>
          </div>
        </>
      )}

      {confirmDialog && (
        <ConfirmDialog
          title={confirmDialog.title}
          message={confirmDialog.message}
          confirmLabel={confirmDialog.confirmLabel}
          busyLabel={confirmDialog.busyLabel}
          confirming={pendingConfirm?.kind === "deleteExperience" ? deleting : confirmBusy}
          onConfirm={handleConfirm}
          onCancel={() => setPendingConfirm(null)}
        />
      )}
    </div>
  );
}
