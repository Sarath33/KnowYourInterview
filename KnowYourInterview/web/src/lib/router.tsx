import { createContext, useCallback, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";

/**
 * Minimal client-side router built directly on the History API — no react-router-dom.
 *
 * Why hand-rolled: the app previously navigated purely through React state (`view` /
 * `selectedExperienceId` in App.tsx), never touching `window.history`. That meant the
 * browser's Back button had nothing to step through — it jumped straight to whatever page
 * existed before the app was loaded, leaving the app entirely instead of walking back
 * through screens. The fix is a real URL per screen, backed by `pushState`/`popstate`, so
 * Back/Forward behave the way they do on every other site. A full router library (e.g.
 * react-router-dom) would be the more common way to do this, but this app only has ~7
 * flat routes plus one nested detail route — small enough to hand-roll safely, and worth
 * doing given this dev sandbox has no network access to install/type-check a new
 * dependency against.
 *
 * `idx` tracks how many in-app entries deep the current page is (stashed in
 * history.state so it survives real Back/Forward, not just our own navigate() calls).
 * That's what lets goBack() tell "there's an in-app screen to return to" apart from "this
 * is the first screen this tab has ever shown" — the latter needs a fallback path instead
 * of calling history.back(), or it would do exactly what this whole file exists to fix:
 * leave the app.
 */
interface RouterContextValue {
  pathname: string;
  /** The query string including its leading "?", or "" when there isn't one. Kept separate
   * from pathname so route matching stays a plain path comparison — only /reset-password
   * reads a param today (the single-use token from the emailed link). */
  search: string;
  canGoBack: boolean;
  navigate: (to: string, opts?: { replace?: boolean }) => void;
  /** Real Back when there's an in-app entry to return to (so it collapses correctly with
   * further physical Back presses); otherwise replaces with `fallback` instead of risking
   * a call to history.back() that exits the app. */
  goBack: (fallback: string) => void;
}

const RouterContext = createContext<RouterContextValue | undefined>(undefined);

/** Splits "/reset-password?token=abc" into ["/reset-password", "?token=abc"]. A path with no
 * query yields an empty string for the second half, matching window.location.search. */
function splitPath(to: string): [string, string] {
  const queryStart = to.indexOf("?");
  return queryStart === -1 ? [to, ""] : [to.slice(0, queryStart), to.slice(queryStart)];
}

function readIdx(): number {
  const state = window.history.state as { idx?: number } | null;
  return typeof state?.idx === "number" ? state.idx : 0;
}

export function RouterProvider({ children }: { children: ReactNode }) {
  const [pathname, setPathname] = useState(() => window.location.pathname);
  const [search, setSearch] = useState(() => window.location.search);
  const [idx, setIdx] = useState(() => readIdx());

  useEffect(() => {
    // Seed history.state on first mount if this tab's very first entry has none yet (a
    // fresh load never went through navigate()), so idx is well-defined from the start.
    // Preserve the query string while doing it — a reset-password link arrives as a fresh
    // load carrying ?token=…, and replacing the entry with the bare pathname would drop it.
    if (window.history.state == null || typeof (window.history.state as { idx?: number }).idx !== "number") {
      window.history.replaceState({ idx: 0 }, "", window.location.pathname + window.location.search);
    }
    const onPopState = () => {
      setPathname(window.location.pathname);
      setSearch(window.location.search);
      setIdx(readIdx());
    };
    window.addEventListener("popstate", onPopState);
    return () => window.removeEventListener("popstate", onPopState);
  }, []);

  const navigate = useCallback(
    (to: string, opts?: { replace?: boolean }) => {
      // `to` may carry a query string; compare against the full current URL so navigating
      // from /reset-password?token=x to /reset-password (dropping the token) still counts
      // as a move rather than a no-op.
      if (to === window.location.pathname + window.location.search) return;
      const [nextPathname, nextQuery] = splitPath(to);
      const nextIdx = opts?.replace ? idx : idx + 1;
      if (opts?.replace) {
        window.history.replaceState({ idx: nextIdx }, "", to);
      } else {
        window.history.pushState({ idx: nextIdx }, "", to);
      }
      setPathname(nextPathname);
      setSearch(nextQuery);
      setIdx(nextIdx);
    },
    [idx],
  );

  const goBack = useCallback(
    (fallback: string) => {
      if (idx > 0) {
        window.history.back();
      } else {
        navigate(fallback, { replace: true });
      }
    },
    [idx, navigate],
  );

  return (
    <RouterContext.Provider value={{ pathname, search, canGoBack: idx > 0, navigate, goBack }}>
      {children}
    </RouterContext.Provider>
  );
}

export function useRouter(): RouterContextValue {
  const ctx = useContext(RouterContext);
  if (!ctx) throw new Error("useRouter must be used within a RouterProvider");
  return ctx;
}
