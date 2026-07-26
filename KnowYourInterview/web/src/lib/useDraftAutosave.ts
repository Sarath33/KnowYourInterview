import { useCallback, useEffect, useRef, useState } from "react";

const DEBOUNCE_MS = 800;

interface StoredDraft<T> {
  value: T;
  savedAt: string; // ISO timestamp — surfaced in the "restored" banner so it's clear this isn't live data.
}

/** Debounced localStorage-backed autosave for a form that isn't safely persisted
 * server-side yet — protects a long teaser/prep-advice write-up from being lost to an
 * accidental tab close or navigation before the draft is actually created/saved.
 * Browser-only and ephemeral by design: this is a safety net for in-progress typing, not
 * a substitute for the real edit-history feature (ExperienceService#listEditHistory),
 * which is server-side, permanent, and answers a different question ("what changed
 * between two saved versions" vs. "don't lose what I haven't saved yet").
 *
 * Usage: call once per form. `restored` (if non-null) is whatever was saved under `key`
 * the last time this ran without being cleared — the caller decides whether to load it
 * into form state and shows its own "restore this?" UI; the hook doesn't do that
 * automatically; silently overwriting a component's initial state on every mount would
 * be surprising. Call `save(value)` on every change (already debounced internally), and
 * `clear()` once the form is actually submitted or the draft is explicitly discarded. */
export function useDraftAutosave<T>(key: string) {
  const [restored, setRestored] = useState<StoredDraft<T> | null>(() => {
    try {
      const raw = window.localStorage.getItem(key);
      return raw ? (JSON.parse(raw) as StoredDraft<T>) : null;
    } catch {
      // Corrupt JSON, private-browsing storage denial, etc. — treat as "nothing to restore".
      return null;
    }
  });
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
    };
  }, []);

  const save = useCallback(
    (value: T) => {
      if (timeoutRef.current) clearTimeout(timeoutRef.current);
      timeoutRef.current = setTimeout(() => {
        try {
          const draft: StoredDraft<T> = { value, savedAt: new Date().toISOString() };
          window.localStorage.setItem(key, JSON.stringify(draft));
        } catch {
          // Storage full or unavailable — autosave is a nice-to-have, not worth
          // surfacing an error over.
        }
      }, DEBOUNCE_MS);
    },
    [key],
  );

  const clear = useCallback(() => {
    if (timeoutRef.current) clearTimeout(timeoutRef.current);
    try {
      window.localStorage.removeItem(key);
    } catch {
      // ignore
    }
    // Without this, `restored` would keep pointing at the now-cleared draft for the rest
    // of this component's lifetime (it's only computed once, at mount) — a caller that
    // re-checks `restored` after calling clear() (e.g. "New draft" clicked again after an
    // explicit discard) would otherwise silently bring the discarded draft right back.
    setRestored(null);
  }, [key]);

  return { restored, save, clear };
}
