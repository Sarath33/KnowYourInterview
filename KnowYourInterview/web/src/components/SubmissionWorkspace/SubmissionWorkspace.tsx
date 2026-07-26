import { useState } from "react";
import type { RoundRequest } from "../../../../shared/types";
import * as api from "../../lib/api";
import { useAsync } from "../../lib/useAsync";
import { errorMessage } from "../../lib/errors";
import { StatusTag } from "../tags";
import { PlusIcon } from "../icons";
import { AddRoundForm } from "./AddRoundForm";
import { RoundCard } from "./RoundCard";
import { ExperienceDetailsForm } from "./ExperienceDetailsForm";
import { SubmissionDetail } from "./SubmissionDetail";
import { emptyDetailsForm, toExperienceRequest, validateDetails } from "./types";
import type { DetailsFormState } from "./types";

export function SubmissionWorkspace() {
  const { data, loading, error: loadError, refetch } = useAsync(() => api.listMyExperiences(), []);
  const experiences = data ?? [];

  const [actionError, setActionError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState<DetailsFormState>(emptyDetailsForm);
  const [pendingRounds, setPendingRounds] = useState<RoundRequest[]>([]);
  const [editingPendingIndex, setEditingPendingIndex] = useState<number | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const error = actionError ?? loadError;

  // Not a <form onSubmit> — the rounds section below embeds AddRoundForm, which is
  // itself a <form>, and nested <form> elements aren't valid HTML. "Create draft" is a
  // plain button that calls this directly instead.
  const handleCreate = async () => {
    setActionError(null);
    const validationError = validateDetails(form);
    if (validationError) {
      setActionError(validationError);
      return;
    }
    try {
      const created = await api.createExperience(toExperienceRequest(form));
      // The draft exists now — add any rounds queued up before creation. If one fails,
      // the draft itself is safe (already saved); surface the error after reloading and
      // still land on the draft so the rest can be added there directly.
      let roundError: string | null = null;
      for (const round of pendingRounds) {
        try {
          await api.addRound(created.id, round);
        } catch (err) {
          roundError = `Draft created, but adding a round failed: ${errorMessage(err)}. You can add the rest from the draft.`;
          break;
        }
      }
      setForm(emptyDetailsForm);
      setPendingRounds([]);
      setCreating(false);
      await refetch();
      setSelectedId(created.id);
      if (roundError) setActionError(roundError);
    } catch (err) {
      setActionError(errorMessage(err));
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
        <p className="muted" aria-busy="true" aria-live="polite">
          Loading…
        </p>
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
                setForm(emptyDetailsForm);
                setPendingRounds([]);
                setEditingPendingIndex(null);
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
                <div className="stack-md">
                  <ExperienceDetailsForm form={form} onChange={setForm} idPrefix="nd" />
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
                experience={selected}
                onChanged={refetch}
                onDeleted={() => {
                  setSelectedId(null);
                  refetch();
                }}
              />
            )}
          </div>
        </div>
      )}
    </div>
  );
}
