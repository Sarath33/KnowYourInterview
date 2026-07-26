import type { ExperienceOutcome } from "../../../../shared/types";
import { OUTCOMES } from "./types";
import type { DetailsFormState } from "./types";

/** Controlled field group for every top-level experience field (company, role, level,
 * location, remote, interview month/year, outcome, teaser, prep advice, overall
 * difficulty, timeline, compensation). Deliberately renders no <form> wrapper and no
 * submit button so it can be embedded both inside the New draft flow (whose "Create draft"
 * button lives alongside a nested AddRoundForm, so it can't be a real <form>) and inside
 * EditDetailsForm's <form>. Both paths convert via toDetailsForm/toExperienceRequest. */
export function ExperienceDetailsForm({
  form,
  onChange,
  idPrefix,
}: {
  form: DetailsFormState;
  onChange: (form: DetailsFormState) => void;
  idPrefix: string;
}) {
  const id = (field: string) => `${idPrefix}-${field}`;
  return (
    <>
      <div className="form-grid-2">
        <div className="field">
          <label className="field-label" htmlFor={id("company")}>
            Company
          </label>
          <input
            id={id("company")}
            className="text-input"
            value={form.company}
            onChange={(e) => onChange({ ...form, company: e.target.value })}
            required
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("role")}>
            Role / title
          </label>
          <input
            id={id("role")}
            className="text-input"
            value={form.roleTitle}
            onChange={(e) => onChange({ ...form, roleTitle: e.target.value })}
            required
          />
        </div>
      </div>
      <div className="form-grid-2">
        <div className="field">
          <label className="field-label" htmlFor={id("level")}>
            Level
          </label>
          <input
            id={id("level")}
            placeholder="e.g. L4, Senior"
            className="text-input"
            value={form.level}
            onChange={(e) => onChange({ ...form, level: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("location")}>
            Location
          </label>
          <input
            id={id("location")}
            className="text-input"
            value={form.location}
            onChange={(e) => onChange({ ...form, location: e.target.value })}
          />
        </div>
      </div>
      <label className="checkbox-field">
        <input
          type="checkbox"
          checked={form.isRemote}
          onChange={(e) => onChange({ ...form, isRemote: e.target.checked })}
        />
        Remote
      </label>
      <div className="form-grid-3">
        <div className="field">
          <label className="field-label" htmlFor={id("month")}>
            Interview month
          </label>
          <input
            id={id("month")}
            type="number"
            min={1}
            max={12}
            className="text-input"
            value={form.interviewMonth}
            onChange={(e) => onChange({ ...form, interviewMonth: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("year")}>
            Interview year
          </label>
          <input
            id={id("year")}
            type="number"
            min={2000}
            className="text-input"
            value={form.interviewYear}
            onChange={(e) => onChange({ ...form, interviewYear: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("outcome")}>
            Outcome
          </label>
          <select
            id={id("outcome")}
            className="select"
            value={form.outcome}
            onChange={(e) => onChange({ ...form, outcome: e.target.value as ExperienceOutcome })}
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
        <label className="field-label" htmlFor={id("teaser")}>
          Teaser — 1-2 public sentences, no question specifics
        </label>
        <textarea
          id={id("teaser")}
          rows={3}
          className="textarea"
          value={form.teaser}
          onChange={(e) => onChange({ ...form, teaser: e.target.value })}
          required
        />
      </div>
      <div className="field">
        <label className="field-label" htmlFor={id("prep")}>
          Prep advice
        </label>
        <textarea
          id={id("prep")}
          rows={3}
          className="textarea"
          value={form.prepAdvice}
          onChange={(e) => onChange({ ...form, prepAdvice: e.target.value })}
        />
      </div>
      <div className="form-grid-3">
        <div className="field">
          <label className="field-label" htmlFor={id("difficulty")}>
            Overall difficulty (1-5)
          </label>
          <input
            id={id("difficulty")}
            type="number"
            min={1}
            max={5}
            className="text-input"
            value={form.overallDifficulty}
            onChange={(e) => onChange({ ...form, overallDifficulty: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("timeline")}>
            Timeline
          </label>
          <input
            id={id("timeline")}
            placeholder="e.g. Applied to offer in 3 weeks"
            className="text-input"
            value={form.timeline}
            onChange={(e) => onChange({ ...form, timeline: e.target.value })}
          />
        </div>
        <div className="field">
          <label className="field-label" htmlFor={id("compensation")}>
            Compensation
          </label>
          <input
            id={id("compensation")}
            placeholder="e.g. 35 LPA"
            className="text-input"
            value={form.compensation}
            onChange={(e) => onChange({ ...form, compensation: e.target.value })}
          />
        </div>
      </div>
    </>
  );
}
