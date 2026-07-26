import { useState } from "react";
import type { FormEvent } from "react";
import type { ExperienceFull, ExperienceRequest } from "../../../../shared/types";
import { errorMessage } from "../../lib/errors";
import { ExperienceDetailsForm } from "./ExperienceDetailsForm";
import { toDetailsForm, toExperienceRequest, validateDetails } from "./types";
import type { DetailsFormState } from "./types";

/** Edits every top-level field on an existing draft/pending/rejected submission. Wraps the
 * shared <ExperienceDetailsForm> in a real <form> and wires it to
 * api.updateExperience/ExperienceService#updateDraft via onSave. */
export function EditDetailsForm({
  experience,
  onSave,
  onCancel,
}: {
  experience: ExperienceFull;
  onSave: (body: ExperienceRequest) => Promise<void>;
  onCancel: () => void;
}) {
  const [form, setForm] = useState<DetailsFormState>(() => toDetailsForm(experience));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const validationError = validateDetails(form);
    if (validationError) {
      setError(validationError);
      return;
    }
    setSaving(true);
    try {
      await onSave(toExperienceRequest(form));
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
      <ExperienceDetailsForm form={form} onChange={setForm} idPrefix="ed" />
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
