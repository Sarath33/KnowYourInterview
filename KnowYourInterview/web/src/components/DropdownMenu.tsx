import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { CheckIcon, ChevronDownIcon } from "./icons";

export interface DropdownOption<T extends string> {
  value: T;
  label: string;
  icon?: ReactNode;
}

/**
 * A branded stand-in for a native <select> — the native element's closed-state box can be
 * restyled (see .select's custom chevron in App.css), but its open dropdown list is
 * rendered by the OS/browser and can't be touched by CSS at all. This renders both states
 * itself: a trigger button showing the current option's icon + label, and a floating panel
 * of option buttons (each with its own icon and a checkmark on the active one) that closes
 * on an outside click, Escape, or picking an option. Generic over the option's value type
 * so callers get type-checked onChange values instead of a raw string.
 */
export function DropdownMenu<T extends string>({
  value,
  options,
  onChange,
  ariaLabel,
  minWidth = 190,
}: {
  value: T;
  options: DropdownOption<T>[];
  onChange: (value: T) => void;
  ariaLabel: string;
  /** Trigger button's min-width in px — the sort dropdown's longer labels need more room
   * than a short filter like Pricing (All/Paid/Free), so this defaults generously and
   * callers with a tighter/known-narrow option set can override it. */
  minWidth?: number;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onClickAway = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", onClickAway);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onClickAway);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  const current = options.find((o) => o.value === value);

  return (
    <div className="dropdown-menu" ref={rootRef}>
      <button
        type="button"
        className="dropdown-menu-trigger"
        style={{ minWidth }}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((o) => !o)}
      >
        <span className="dropdown-menu-trigger-content">
          {current?.icon}
          {current?.label ?? ariaLabel}
        </span>
        <ChevronDownIcon />
      </button>
      {open && (
        <div className="dropdown-menu-panel" role="listbox" aria-label={ariaLabel}>
          {options.map((opt) => (
            <button
              key={opt.value}
              type="button"
              role="option"
              aria-selected={opt.value === value}
              className={`dropdown-menu-item${opt.value === value ? " is-active" : ""}`}
              onClick={() => {
                onChange(opt.value);
                setOpen(false);
              }}
            >
              {opt.icon}
              <span className="dropdown-menu-item-label">{opt.label}</span>
              {opt.value === value && <CheckIcon size={13} />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
