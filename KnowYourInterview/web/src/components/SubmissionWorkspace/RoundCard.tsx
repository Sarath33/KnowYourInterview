import { roundTypeLabel } from "./types";
import type { RoundLike } from "./types";

/** Inset card for a single round — used both for a submission's already-saved rounds
 * and for rounds queued up on a not-yet-created draft. */
export function RoundCard({
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
