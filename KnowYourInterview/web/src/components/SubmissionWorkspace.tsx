import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import type { ExperienceFull, ExperienceOutcome, ExperienceRequest } from "../../../shared/types";
import * as api from "../lib/api";
import { ApiError } from "../lib/api";
import { useAuth } from "../context/AuthContext";

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
    <div style={{ marginTop: "1.5rem" }}>
      <h2>My submissions</h2>
      {error && <p style={{ color: "red" }}>{error}</p>}
      {loading ? (
        <p>Loading…</p>
      ) : (
        <div style={{ display: "flex", gap: "2rem" }}>
          <div style={{ minWidth: 220 }}>
            <ul style={{ listStyle: "none", padding: 0 }}>
              {experiences.map((exp) => (
                <li key={exp.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(exp.id)}
                    style={{
                      display: "block",
                      width: "100%",
                      textAlign: "left",
                      margin: "0.25rem 0",
                      fontWeight: exp.id === selectedId ? "bold" : "normal",
                    }}
                  >
                    {exp.company} — {exp.roleTitle} <em>({exp.status})</em>
                  </button>
                </li>
              ))}
            </ul>
            {!creating && (
              <button type="button" onClick={() => setCreating(true)}>
                + New draft
              </button>
            )}
          </div>

          <div style={{ flex: 1 }}>
            {creating && (
              <form onSubmit={handleCreate} style={{ display: "flex", flexDirection: "column", gap: "0.5rem", maxWidth: 400 }}>
                <h3>New draft</h3>
                <input
                  placeholder="Company"
                  value={form.company}
                  onChange={(e) => setForm({ ...form, company: e.target.value })}
                  required
                />
                <input
                  placeholder="Role / title"
                  value={form.roleTitle}
                  onChange={(e) => setForm({ ...form, roleTitle: e.target.value })}
                  required
                />
                <input
                  placeholder="Level (e.g. L4, Senior)"
                  value={form.level}
                  onChange={(e) => setForm({ ...form, level: e.target.value })}
                />
                <input
                  placeholder="Location"
                  value={form.location}
                  onChange={(e) => setForm({ ...form, location: e.target.value })}
                />
                <label>
                  <input
                    type="checkbox"
                    checked={form.isRemote}
                    onChange={(e) => setForm({ ...form, isRemote: e.target.checked })}
                  />{" "}
                  Remote
                </label>
                <select
                  value={form.outcome}
                  onChange={(e) => setForm({ ...form, outcome: e.target.value as ExperienceOutcome })}
                >
                  {OUTCOMES.map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
                <textarea
                  placeholder="Teaser — 1-2 public sentences, no question specifics"
                  value={form.teaser}
                  onChange={(e) => setForm({ ...form, teaser: e.target.value })}
                  required
                />
                <div>
                  <button type="submit">Create draft</button>{" "}
                  <button type="button" onClick={() => setCreating(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            )}

            {selected && !creating && <ExperienceDetail experience={selected} token={token} onChanged={load} />}
          </div>
        </div>
      )}
    </div>
  );
}

function ExperienceDetail({
  experience,
  token,
  onChanged,
}: {
  experience: ExperienceFull;
  token: string;
  onChanged: () => void;
}) {
  const [roundType, setRoundType] = useState("");
  const [error, setError] = useState<string | null>(null);
  const isDraft = experience.status === "DRAFT";

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

  return (
    <div>
      <h3>
        {experience.company} — {experience.roleTitle}
      </h3>
      <p>
        Status: <strong>{experience.status}</strong>
        {experience.status === "REJECTED" && experience.rejectionReason && (
          <> — {experience.rejectionReason}</>
        )}
      </p>
      <p>Price to viewers: ₹{(experience.pricePaise / 100).toFixed(2)} (platform-set)</p>
      {error && <p style={{ color: "red" }}>{error}</p>}

      <h4>Rounds ({experience.rounds.length})</h4>
      <ul>
        {experience.rounds.map((r) => (
          <li key={r.id}>
            #{r.roundNumber} {r.roundType}
            {isDraft && (
              <>
                {" "}
                <button type="button" onClick={() => handleDeleteRound(r.id)}>
                  Remove
                </button>
              </>
            )}
          </li>
        ))}
      </ul>
      {isDraft && (
        <form onSubmit={handleAddRound} style={{ display: "flex", gap: "0.5rem" }}>
          <input
            placeholder="Round type (e.g. PHONE_SCREEN, ONSITE, SYSTEM_DESIGN)"
            value={roundType}
            onChange={(e) => setRoundType(e.target.value)}
            required
          />
          <button type="submit">Add round</button>
        </form>
      )}

      <h4>Proof documents ({experience.proofDocuments.length})</h4>
      <ul>
        {experience.proofDocuments.map((p) => (
          <li key={p.id}>
            {p.fileName}{" "}
            <button type="button" onClick={() => api.openProof(token, experience.id, p.id).catch((e) => setError(errorMessage(e)))}>
              View
            </button>
          </li>
        ))}
      </ul>
      {isDraft && <input type="file" onChange={handleUpload} />}

      {isDraft && (
        <p>
          <button type="button" onClick={handleSubmit}>
            Submit for review
          </button>{" "}
          <span style={{ fontSize: "0.85em", color: "#666" }}>
            (needs at least one round and one proof document)
          </span>
        </p>
      )}
    </div>
  );
}
