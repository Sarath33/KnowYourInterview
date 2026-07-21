import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { ExperienceFull, ExperienceOutcome, ExperienceRequest } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { StatusTag } from "./tags";
import { FileTextIcon, PlusIcon } from "./icons";

const OUTCOMES: ExperienceOutcome[] = ["OFFER", "REJECTED", "WITHDRAWN"];

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

export function SubmissionWorkspace() {
  const { accessToken } = useAuth();
  const [experiences, setExperiences] = useState<ExperienceFull[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<ExperienceRequest>(emptyForm);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const token = accessToken!;

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

  const handleCreate = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      const created = await api.createExperience(token, form);
      setForm(emptyForm);
      setCreating(false);
      await load();
      setSelectedId(created.id);
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
                setForm(emptyForm);
              }}
              className="btn-dashed"
            >
              New draft
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
                <form onSubmit={handleCreate} className="stack-md">
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
                  <div className="row">
                    <button type="submit" className="btn btn-primary">
                      Create draft
                    </button>
                    <button type="button" onClick={() => setCreating(false)} className="btn btn-outline">
                      Cancel
                    </button>
                  </div>
                </form>
              </div>
            )}

            {selected && !creating && (
              <SubmissionDetail
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
  const [roundType, setRoundType] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);
  // Draft (never submitted) or rejected (sent back with a reason) — both are fully
  // editable: add/remove rounds, upload/delete proof, delete the whole thing, or submit
  // (resubmit, for a rejected one) for review. Matches ExperienceService#requireEditable.
  const isEditable = experience.status === "DRAFT" || experience.status === "REJECTED";

  const handleAddRound = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    try {
      await api.addRound(token, experience.id, { roundType });
      setRoundType("");
      onChanged();
    } catch (err) {
      setError(errorMessage(err));
    }
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

  return (
    <div className="card card-pad-md">
      <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 22 }}>
        {experience.company} — {experience.roleTitle}
      </div>
      <div className="row" style={{ margin: "10px 0 18px" }}>
        <StatusTag status={experience.status} />
        <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
          {experience.status === "PUBLISHED" || experience.status === "APPROVED"
            ? `₹${(experience.pricePaise / 100).toFixed(2)} to viewers`
            : "Price is set by the platform on publish"}
        </span>
      </div>
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
      <table className="data-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Type</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          {experience.rounds.map((r) => (
            <tr key={r.id}>
              <td>{r.roundNumber}</td>
              <td>{r.roundType}</td>
              <td style={{ textAlign: "right" }}>
                {isEditable && (
                  <button type="button" onClick={() => handleDeleteRound(r.id)} className="btn-danger-text">
                    Remove
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {isEditable && (
        <form onSubmit={handleAddRound} className="row" style={{ marginTop: 16, flexWrap: "nowrap" }}>
          <input
            placeholder="Round type (e.g. PHONE_SCREEN, ONSITE, SYSTEM_DESIGN)"
            value={roundType}
            onChange={(e) => setRoundType(e.target.value)}
            required
            className="text-input"
            style={{ flex: 1 }}
          />
          <button type="submit" className="btn btn-outline">
            Add round
          </button>
        </form>
      )}

      <div className="divider" />
      <div className="section-title" style={{ fontSize: 16 }}>
        Proof documents ({experience.proofDocuments.length})
      </div>
      <div className="stack-sm">
        {experience.proofDocuments.map((p) => (
          <div key={p.id} className="file-row">
            <FileTextIcon />
            <span>{p.fileName}</span>
            {isEditable && (
              <button type="button" onClick={() => handleDeleteProof(p.id)} className="btn-danger-text">
                Remove
              </button>
            )}
          </div>
        ))}
      </div>
      {isEditable && (
        <div style={{ marginTop: 12 }}>
          <input type="file" onChange={handleUpload} />
        </div>
      )}

      {error && <p className="error-text" style={{ marginTop: 12 }}>{error}</p>}
      {isEditable && (
        <>
          <div className="divider" />
          <div className="row" style={{ justifyContent: "space-between" }}>
            <div className="row">
              <button type="button" onClick={handleSubmit} className="btn btn-primary">
                {experience.status === "REJECTED" ? "Resubmit for review" : "Submit for review"}
              </button>
              <span style={{ fontSize: 13, color: "var(--text-muted)" }}>
                Needs at least one round and one proof document.
              </span>
            </div>
            <button
              type="button"
              onClick={handleDeleteExperience}
              disabled={deleting}
              className="btn btn-outline btn-outline-danger"
            >
              {deleting ? "Deleting…" : "Delete submission"}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
