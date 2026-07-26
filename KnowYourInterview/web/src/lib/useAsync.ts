import { useCallback, useEffect, useRef, useState } from "react";
import type { DependencyList } from "react";
import { errorMessage } from "./errors";

interface AsyncState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

export interface UseAsyncResult<T> extends AsyncState<T> {
  /** Re-run the async function. Safe to call after mutations to reload. */
  refetch: () => Promise<void>;
  /** Optimistically replace the current data without a network round-trip. */
  setData: (updater: T | ((prev: T | null) => T)) => void;
}

/**
 * Dependency-free replacement for the hand-rolled `{data, loading, error}` +
 * `load()` + `useEffect` triplet that every list view was duplicating. Re-runs `fn`
 * whenever `deps` change and, crucially, tags each run with an incrementing id so a
 * slow earlier response can't clobber a newer one — fixing the out-of-order race in the
 * paginated/filtered views. Pass `{ enabled: false }` to hold off until preconditions
 * (e.g. a token) are met.
 */
export function useAsync<T>(
  fn: () => Promise<T>,
  deps: DependencyList,
  options: { enabled?: boolean } = {},
): UseAsyncResult<T> {
  const enabled = options.enabled ?? true;
  const [state, setState] = useState<AsyncState<T>>({ data: null, loading: enabled, error: null });

  // Keep the latest fn without making it a dependency of the effect — callers commonly
  // pass an inline closure, and we want re-runs driven by `deps`, not identity churn.
  const fnRef = useRef(fn);
  fnRef.current = fn;

  // Monotonic request id: only the most recently started run is allowed to commit.
  const requestId = useRef(0);

  const run = useCallback(async () => {
    const id = ++requestId.current;
    setState((prev) => ({ ...prev, loading: true, error: null }));
    try {
      const data = await fnRef.current();
      if (id === requestId.current) setState({ data, loading: false, error: null });
    } catch (err) {
      if (id === requestId.current) setState({ data: null, loading: false, error: errorMessage(err) });
    }
  }, []);

  useEffect(() => {
    if (!enabled) {
      // Cancel any in-flight commit and reset to an idle state.
      requestId.current++;
      setState({ data: null, loading: false, error: null });
      return;
    }
    run();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, run, ...deps]);

  const setData = useCallback((updater: T | ((prev: T | null) => T)) => {
    setState((prev) => ({
      ...prev,
      data:
        typeof updater === "function"
          ? (updater as (p: T | null) => T)(prev.data)
          : updater,
    }));
  }, []);

  return { ...state, refetch: run, setData };
}
