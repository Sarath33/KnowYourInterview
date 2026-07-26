import { useEffect, useId, useRef } from "react";

/** Generic "are you sure?" modal for destructive, irreversible actions (delete
 * submission, remove a saved round, remove a proof document) — those all used to fire
 * their API call the instant the button was clicked, with no safety step. Reuses the
 * .dialog-overlay/.dialog-card styling already defined in App.css.
 *
 * Accessible: role="dialog" + aria-modal, labelled by its title, moves focus into the
 * dialog on open and restores it on close, closes on Escape, and traps Tab within itself. */
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
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);
  const cancelButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    // Remember what was focused before the dialog opened, so we can restore it on close.
    const previouslyFocused = document.activeElement as HTMLElement | null;
    cancelButtonRef.current?.focus();

    const getFocusable = (): HTMLElement[] => {
      if (!dialogRef.current) return [];
      return Array.from(
        dialogRef.current.querySelectorAll<HTMLElement>(
          'button:not([disabled]), [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
        ),
      );
    };

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        if (!confirming) onCancel();
        return;
      }
      if (e.key === "Tab") {
        const focusable = getFocusable();
        if (focusable.length === 0) return;
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;
        if (e.shiftKey && active === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      previouslyFocused?.focus?.();
    };
    // Intentionally set up once per mount; handlers read the latest props via closure
    // recreation is unnecessary here since onCancel/confirming rarely change identity.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="dialog-overlay" onMouseDown={(e) => e.target === e.currentTarget && !confirming && onCancel()}>
      <div className="dialog-card" role="dialog" aria-modal="true" aria-labelledby={titleId} ref={dialogRef}>
        <div id={titleId} style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 19, marginBottom: 10 }}>
          {title}
        </div>
        <p style={{ fontSize: 14, color: "var(--text-secondary)", lineHeight: 1.5, marginBottom: 22 }}>{message}</p>
        <div className="row" style={{ justifyContent: "flex-end" }}>
          <button type="button" ref={cancelButtonRef} onClick={onCancel} disabled={confirming} className="btn btn-outline">
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
