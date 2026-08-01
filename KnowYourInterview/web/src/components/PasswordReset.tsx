import { useState } from "react";
import type { FormEvent, ReactNode } from "react";
import * as api from "../lib/api";
import { errorMessage } from "../lib/errors";

/**
 * The two halves of password recovery, which existed as backend endpoints
 * (POST /auth/forgot-password and /auth/reset-password, both implemented, rate-limited and
 * token-hashed since Phase 2) with no way to reach them from the app at all: no link, no
 * screen, no route. A user who forgot their password was simply locked out.
 *
 * Delivery caveat, deliberately surfaced in the UI rather than hidden: no email provider is
 * wired up yet, so AuthService#forgotPassword logs the reset link server-side instead of
 * sending it. RequestReset therefore tells the user the link may need to come from an
 * operator, instead of claiming an email is on its way. Swap that copy for the plain
 * "check your inbox" version the moment a provider is configured — that's the only change
 * this screen needs.
 */

/** Step 1: ask for a link. The success message is intentionally identical whether or not the
 * address is registered, mirroring the backend's no-user-enumeration response. */
export function RequestPasswordReset({ onBackToLogin }: { onBackToLogin: () => void }) {
  const [email, setEmail] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.forgotPassword({ email });
      setSent(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthCard title="Reset your password">
      {sent ? (
        <>
          <p className="auth-card-text">
            If an account exists for <strong>{email}</strong>, a reset link has been generated. It's valid for one
            hour and can only be used once.
          </p>
          <p className="auth-card-note">
            Email delivery isn't set up on this deployment yet, so the link is written to the server logs rather
            than sent to your inbox — ask whoever operates this instance for it.
          </p>
          <button type="button" onClick={onBackToLogin} className="btn btn-outline btn-block">
            Back to log in
          </button>
        </>
      ) : (
        <form onSubmit={handleSubmit} className="stack-md">
          <p className="auth-card-text">
            Enter the email you signed up with and we'll generate a single-use link to set a new password.
          </p>
          <div className="field">
            <label htmlFor="forgot-email" className="field-label">
              Email
            </label>
            <input
              id="forgot-email"
              type="email"
              className="text-input"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              required
            />
          </div>
          {error && <p className="error-text">{error}</p>}
          <button type="submit" disabled={submitting} className="btn btn-primary btn-block">
            {submitting ? "Please wait…" : "Send reset link"}
          </button>
          <button type="button" onClick={onBackToLogin} className="btn-ghost">
            Back to log in
          </button>
        </form>
      )}
    </AuthCard>
  );
}

/** Step 2: the screen the reset link lands on. `token` comes from the URL's ?token= param —
 * a missing one is treated as a broken link rather than rendering a form that can only fail. */
export function ResetPassword({ token, onDone }: { token: string | null; onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    // Checked here rather than left to the backend: the API only ever sees one password, so
    // a typo in a field the user can't read back would otherwise set a password they don't
    // know — and the token is single-use, so there'd be no second attempt.
    if (password !== confirmation) {
      setError("Those passwords don't match.");
      return;
    }
    if (!token) return;
    setError(null);
    setSubmitting(true);
    try {
      await api.resetPassword({ token, newPassword: password });
      setDone(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  if (!token) {
    return (
      <AuthCard title="That link looks incomplete">
        <p className="auth-card-text">
          This reset link is missing its token, so there's nothing to verify. Links expire an hour after they're
          created — request a fresh one and use the whole URL.
        </p>
        <button type="button" onClick={onDone} className="btn btn-primary btn-block">
          Back to log in
        </button>
      </AuthCard>
    );
  }

  if (done) {
    return (
      <AuthCard title="Password updated">
        <p className="auth-card-text">You can log in with your new password now.</p>
        <button type="button" onClick={onDone} className="btn btn-primary btn-block">
          Log in
        </button>
      </AuthCard>
    );
  }

  return (
    <AuthCard title="Choose a new password">
      <form onSubmit={handleSubmit} className="stack-md">
        <div className="field">
          <label htmlFor="reset-pw" className="field-label">
            New password
          </label>
          <input
            id="reset-pw"
            type="password"
            className="text-input"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            autoComplete="new-password"
            required
          />
          <span className="field-hint">At least 8 characters.</span>
        </div>
        <div className="field">
          <label htmlFor="reset-pw-confirm" className="field-label">
            Confirm new password
          </label>
          <input
            id="reset-pw-confirm"
            type="password"
            className="text-input"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            minLength={8}
            autoComplete="new-password"
            required
          />
        </div>
        {error && <p className="error-text">{error}</p>}
        <button type="submit" disabled={submitting} className="btn btn-primary btn-block">
          {submitting ? "Please wait…" : "Set new password"}
        </button>
        <button type="button" onClick={onDone} className="btn-ghost">
          Back to log in
        </button>
      </form>
    </AuthCard>
  );
}

/** Same card shell AuthForms uses, so the recovery screens don't look like a different app. */
function AuthCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="auth-card-wrap">
      <div className="card card-pad-lg">
        <div className="page-kicker">Account</div>
        <div className="auth-card-title">{title}</div>
        {children}
      </div>
    </div>
  );
}
