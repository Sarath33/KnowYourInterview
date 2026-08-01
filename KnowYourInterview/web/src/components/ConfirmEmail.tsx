import { useEffect, useRef, useState } from "react";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { errorMessage } from "../lib/errors";

type State = "working" | "confirmed" | "failed" | "noToken";

/**
 * The screen a confirmation link lands on. Redeems the token on mount rather than asking the
 * user to press a button — they already expressed intent by clicking the link in their email,
 * and a second click would be pure ceremony.
 *
 * Works signed out. The token is the proof, and the person opening their email is often on a
 * different device from the one they registered on. If they *are* signed in, the session is
 * refreshed on success so the banner disappears and the gated actions unlock immediately,
 * rather than waiting for the 15-minute access token to turn over.
 */
export function ConfirmEmail({
  token,
  onContinue,
}: {
  token: string | null;
  onContinue: () => void;
}) {
  const { isAuthenticated, refreshSession } = useAuth();
  const [state, setState] = useState<State>(token ? "working" : "noToken");
  const [error, setError] = useState<string | null>(null);
  // React 18+ StrictMode double-invokes effects in development. The token is single-use, so
  // without this the second call would consume nothing but would race the first — guard it
  // rather than relying on the backend's already-verified short-circuit to paper over it.
  const attempted = useRef(false);

  useEffect(() => {
    if (!token || attempted.current) return;
    attempted.current = true;

    let cancelled = false;
    (async () => {
      try {
        await api.verifyEmail({ token });
        if (cancelled) return;
        setState("confirmed");
        // Best-effort, and deliberately after the state change: the confirmation has already
        // happened server-side, so a failed refresh shouldn't make this screen look broken.
        if (isAuthenticated) await refreshSession();
      } catch (err) {
        if (cancelled) return;
        setError(errorMessage(err));
        setState("failed");
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [token, isAuthenticated, refreshSession]);

  return (
    <div className="auth-card-wrap">
      <div className="card card-pad-lg">
        <div className="page-kicker">Account</div>

        {state === "working" && (
          <>
            <div className="auth-card-title">Confirming your email…</div>
            <p className="auth-card-text" aria-busy="true" aria-live="polite">
              One moment.
            </p>
          </>
        )}

        {state === "confirmed" && (
          <>
            <div className="auth-card-title">Email confirmed</div>
            <p className="auth-card-text">
              You're all set — you can submit interview experiences and unlock other people's now.
            </p>
            <button type="button" onClick={onContinue} className="btn btn-primary btn-block">
              {isAuthenticated ? "Start browsing" : "Log in"}
            </button>
          </>
        )}

        {state === "noToken" && (
          <>
            <div className="auth-card-title">That link looks incomplete</div>
            <p className="auth-card-text">
              This confirmation link is missing its token, so there's nothing to check. Use the whole URL from
              the email, or request a new link from the app.
            </p>
            <button type="button" onClick={onContinue} className="btn btn-primary btn-block">
              Continue
            </button>
          </>
        )}

        {state === "failed" && (
          <>
            <div className="auth-card-title">We couldn't confirm that link</div>
            <p className="auth-card-text">{error}</p>
            <p className="auth-card-note">
              Confirmation links expire after 24 hours and only work once. Log in and use the "Resend" link in
              the banner to get a fresh one.
            </p>
            <button type="button" onClick={onContinue} className="btn btn-primary btn-block">
              Continue
            </button>
          </>
        )}
      </div>
    </div>
  );
}

/**
 * The persistent nudge for a signed-in but unconfirmed account, shown above every screen.
 *
 * Not dismissible on purpose: it's the only in-app explanation for why "New draft" and
 * "Unlock" are disabled, so hiding it would leave those looking broken. It disappears by
 * being resolved, which is the behaviour we actually want.
 */
export function UnverifiedEmailBanner({ email }: { email: string }) {
  const [status, setStatus] = useState<"idle" | "sending" | "sent" | "failed">("idle");
  const [error, setError] = useState<string | null>(null);

  const resend = async () => {
    setStatus("sending");
    setError(null);
    try {
      await api.resendVerification({ email });
      setStatus("sent");
    } catch (err) {
      setError(errorMessage(err));
      setStatus("failed");
    }
  };

  return (
    <div className="verify-banner" role="status">
      <div>
        <strong>Confirm your email to submit or unlock experiences.</strong>{" "}
        {status === "sent" ? (
          <span>Sent — check {email}, including spam.</span>
        ) : (
          <span>We sent a link to {email} when you signed up.</span>
        )}
        {status === "failed" && error && <div className="verify-banner-error">{error}</div>}
      </div>
      {status !== "sent" && (
        <button type="button" onClick={resend} disabled={status === "sending"} className="btn btn-outline">
          {status === "sending" ? "Sending…" : "Resend link"}
        </button>
      )}
    </div>
  );
}
