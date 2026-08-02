import { useEffect, useRef, useState } from "react";
import type { ChangeEvent, ClipboardEvent, FormEvent, KeyboardEvent } from "react";
import * as api from "../lib/api";
import { useAuth } from "../context/AuthContext";
import { errorMessage } from "../lib/errors";

const CODE_LENGTH = 6;

/**
 * Where a new account types the 6-digit code from its confirmation email.
 *
 * Works signed out, because the common case is reading the code on a phone and typing it on a
 * laptop — or vice versa. When signed in the address is known and shown; when not, it has to be
 * typed, since the code alone doesn't identify an account (see VerifyEmailRequest).
 *
 * On success the session is refreshed if there is one, so the banner clears and the gated
 * actions unlock immediately rather than waiting up to 15 minutes for the access token to turn
 * over.
 */
export function ConfirmEmail({ onContinue }: { onContinue: () => void }) {
  const { user, isAuthenticated, refreshSession } = useAuth();
  const [email, setEmail] = useState(user?.email ?? "");
  const [digits, setDigits] = useState<string[]>(() => Array(CODE_LENGTH).fill(""));
  const [submitting, setSubmitting] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [resendState, setResendState] = useState<"idle" | "sending" | "sent">("idle");
  const inputsRef = useRef<(HTMLInputElement | null)[]>([]);

  // A signed-in user's address arrives asynchronously (the session may still be rehydrating on
  // first paint), so adopt it when it shows up rather than only at mount.
  useEffect(() => {
    if (user?.email) setEmail(user.email);
  }, [user?.email]);

  const code = digits.join("");
  const complete = code.length === CODE_LENGTH && !digits.includes("");

  const focusInput = (index: number) => {
    inputsRef.current[Math.max(0, Math.min(CODE_LENGTH - 1, index))]?.focus();
  };

  const handleDigitChange = (index: number, event: ChangeEvent<HTMLInputElement>) => {
    // Strip anything non-numeric rather than rejecting the keystroke: phone keyboards and
    // autofill both hand over characters this field shouldn't have to argue about.
    const value = event.target.value.replace(/\D/g, "");
    if (!value) {
      setDigits((current) => current.map((d, i) => (i === index ? "" : d)));
      return;
    }
    setError(null);
    setDigits((current) => {
      const next = [...current];
      // Typing (or autofilling) several characters into one box spills into the ones after it,
      // which is what an SMS/email autofill does and what pasting a code feels like.
      for (let offset = 0; offset < value.length && index + offset < CODE_LENGTH; offset++) {
        next[index + offset] = value[offset];
      }
      return next;
    });
    focusInput(index + value.length);
  };

  const handleKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Backspace" && !digits[index] && index > 0) {
      // Backspace in an empty box steps back and clears the previous one — otherwise the
      // caret gets stuck and the field feels broken.
      event.preventDefault();
      setDigits((current) => current.map((d, i) => (i === index - 1 ? "" : d)));
      focusInput(index - 1);
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      focusInput(index - 1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      focusInput(index + 1);
    }
  };

  /** Pasting the whole code is the most likely way it gets entered — people copy it out of the
   * email rather than retyping. Fill every box from wherever the paste lands. */
  const handlePaste = (index: number, event: ClipboardEvent<HTMLInputElement>) => {
    const pasted = event.clipboardData.getData("text").replace(/\D/g, "");
    if (!pasted) return;
    event.preventDefault();
    setError(null);
    setDigits((current) => {
      const next = [...current];
      for (let offset = 0; offset < pasted.length && index + offset < CODE_LENGTH; offset++) {
        next[index + offset] = pasted[offset];
      }
      return next;
    });
    focusInput(index + pasted.length);
  };

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault();
    if (!complete || !email) return;
    setError(null);
    setSubmitting(true);
    try {
      await api.verifyEmail({ email, code });
      setConfirmed(true);
      if (isAuthenticated) await refreshSession();
    } catch (err) {
      setError(errorMessage(err));
      // Clear the boxes on a failure. The code is wrong or spent either way, and leaving the
      // old digits in place invites re-submitting the same thing against a budget of five.
      setDigits(Array(CODE_LENGTH).fill(""));
      focusInput(0);
    } finally {
      setSubmitting(false);
    }
  };

  const resend = async () => {
    if (!email) {
      setError("Enter your email address first.");
      return;
    }
    setResendState("sending");
    setError(null);
    try {
      await api.resendVerification({ email });
      setResendState("sent");
      setDigits(Array(CODE_LENGTH).fill(""));
      focusInput(0);
    } catch (err) {
      setError(errorMessage(err));
      setResendState("idle");
    }
  };

  if (confirmed) {
    return (
      <div className="auth-card-wrap">
        <div className="card card-pad-lg">
          <div className="page-kicker">Account</div>
          <div className="auth-card-title">Email confirmed</div>
          <p className="auth-card-text">
            You're all set — you can submit interview experiences and unlock other people's now.
          </p>
          <button type="button" onClick={onContinue} className="btn btn-primary btn-block">
            {isAuthenticated ? "Start browsing" : "Log in"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="auth-card-wrap">
      <div className="card card-pad-lg">
        <div className="page-kicker">Account</div>
        <div className="auth-card-title">Enter your confirmation code</div>
        <p className="auth-card-text">
          {email ? (
            <>
              We sent a {CODE_LENGTH}-digit code to <strong>{email}</strong>. It expires in 10 minutes.
            </>
          ) : (
            <>Enter the email you signed up with and the {CODE_LENGTH}-digit code we sent you.</>
          )}
        </p>

        <form onSubmit={handleSubmit} className="stack-md">
          {/* Only asked for when we don't already know it — a signed-in user shouldn't have to
              retype their own address. */}
          {!isAuthenticated && (
            <div className="field">
              <label htmlFor="confirm-email" className="field-label">
                Email
              </label>
              <input
                id="confirm-email"
                type="email"
                className="text-input"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                required
              />
            </div>
          )}

          <div className="field">
            <span className="field-label" id="code-label">
              Confirmation code
            </span>
            <div className="otp-inputs" role="group" aria-labelledby="code-label">
              {digits.map((digit, index) => (
                <input
                  key={index}
                  ref={(el) => {
                    inputsRef.current[index] = el;
                  }}
                  // Not type="number": it brings a spinner, allows "e" and "-", and silently
                  // drops leading zeros. inputMode gets the numeric keypad on mobile without
                  // any of that.
                  type="text"
                  inputMode="numeric"
                  autoComplete={index === 0 ? "one-time-code" : "off"}
                  maxLength={CODE_LENGTH}
                  className="otp-input"
                  aria-label={`Digit ${index + 1} of ${CODE_LENGTH}`}
                  value={digit}
                  onChange={(e) => handleDigitChange(index, e)}
                  onKeyDown={(e) => handleKeyDown(index, e)}
                  onPaste={(e) => handlePaste(index, e)}
                  autoFocus={index === 0}
                />
              ))}
            </div>
          </div>

          {error && <p className="error-text">{error}</p>}
          {resendState === "sent" && !error && (
            <p className="muted" style={{ fontSize: 13 }}>
              New code sent — check {email}, including spam.
            </p>
          )}

          <button type="submit" disabled={!complete || submitting} className="btn btn-primary btn-block">
            {submitting ? "Checking…" : "Confirm email"}
          </button>

          <div className="row" style={{ justifyContent: "space-between" }}>
            <button
              type="button"
              onClick={resend}
              disabled={resendState === "sending"}
              className="btn-ghost"
              style={{ fontSize: 13 }}
            >
              {resendState === "sending" ? "Sending…" : "Send a new code"}
            </button>
            <button type="button" onClick={onContinue} className="btn-ghost" style={{ fontSize: 13 }}>
              I'll do this later
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

/**
 * The persistent nudge for a signed-in but unconfirmed account, shown above every screen.
 *
 * Not dismissible on purpose: it's the only in-app explanation for why "New draft" and "Unlock"
 * are disabled, so hiding it would leave those looking broken. It disappears by being resolved.
 */
export function UnverifiedEmailBanner({ email, onEnterCode }: { email: string; onEnterCode: () => void }) {
  return (
    <div className="verify-banner" role="status">
      <div>
        <strong>Confirm your email to submit or unlock experiences.</strong>{" "}
        <span>We sent a code to {email} when you signed up.</span>
      </div>
      <button type="button" onClick={onEnterCode} className="btn btn-outline">
        Enter code
      </button>
    </div>
  );
}
