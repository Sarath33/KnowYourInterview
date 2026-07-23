/** Generic "are you sure?" modal for destructive, irreversible actions (delete
 * submission, remove a saved round, remove a proof document) — those all used to fire
 * their API call the instant the button was clicked, with no safety step. Reuses the
 * .dialog-overlay/.dialog-card styling already defined in App.css. */
export function ConfirmDialog({
  title,
  message,
  confirmLabel,
  busyLabel,
  confirming = false,
  onConfirm,
  onCancel,
}: {
  title: string;
  message: string;
  confirmLabel: string;
  busyLabel?: string;
  confirming?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="dialog-overlay">
      <div className="dialog-card">
        <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19, marginBottom: 10 }}>
          {title}
        </div>
        <p style={{ fontSize: 14, color: "var(--text-secondary)", lineHeight: 1.5, marginBottom: 22 }}>{message}</p>
        <div className="row" style={{ justifyContent: "flex-end" }}>
          <button type="button" onClick={onCancel} disabled={confirming} className="btn btn-outline">
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={confirming}
            className="btn btn-outline btn-outline-danger"
          >
            {confirming ? busyLabel ?? "Working…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
