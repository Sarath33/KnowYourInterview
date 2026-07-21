import { useState } from "react";
import type { FormEvent } from "react";
import { useAuth } from "../context/AuthContext";
import { ApiError } from "../lib/api";

function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    const firstFieldError = err.fieldErrors && Object.values(err.fieldErrors)[0];
    return firstFieldError ?? err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}

export function AuthForms({ onGuestBrowse }: { onGuestBrowse: () => void }) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const { login, register } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (mode === "login") {
        await login(email, password);
      } else {
        await register(email, password, displayName);
      }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ maxWidth: 420, margin: "32px auto 0" }}>
      <div className="card card-pad-lg">
        <div className="page-kicker">Welcome</div>
        <div style={{ fontFamily: "var(--font-heading)", fontWeight: 700, fontSize: 26, marginBottom: 20 }}>
          {mode === "login" ? "Log in" : "Create your account"}
        </div>

        <div
          style={{
            display: "flex",
            gap: 6,
            background: "var(--neutral-bg)",
            borderRadius: 10,
            padding: 4,
            marginBottom: 22,
          }}
        >
          <button
            type="button"
            onClick={() => {
              setMode("login");
              setError(null);
            }}
            style={{
              flex: 1,
              textAlign: "center",
              padding: 8,
              borderRadius: 8,
              cursor: "pointer",
              fontWeight: 600,
              fontSize: 14,
              fontFamily: "inherit",
              border: "none",
              background: mode === "login" ? "var(--surface)" : "transparent",
              color: mode === "login" ? "var(--text-primary)" : "var(--text-muted)",
              boxShadow: mode === "login" ? "0 1px 2px rgba(16,24,40,0.08)" : "none",
            }}
          >
            Log in
          </button>
          <button
            type="button"
            onClick={() => {
              setMode("register");
              setError(null);
            }}
            style={{
              flex: 1,
              textAlign: "center",
              padding: 8,
              borderRadius: 8,
              cursor: "pointer",
              fontWeight: 600,
              fontSize: 14,
              fontFamily: "inherit",
              border: "none",
              background: mode === "register" ? "var(--surface)" : "transparent",
              color: mode === "register" ? "var(--text-primary)" : "var(--text-muted)",
              boxShadow: mode === "register" ? "0 1px 2px rgba(16,24,40,0.08)" : "none",
            }}
          >
            Register
          </button>
        </div>

        <form onSubmit={handleSubmit} className="stack-md">
          {mode === "register" && (
            <div className="field">
              <label htmlFor="reg-name" className="field-label">
                Display name
              </label>
              <input
                id="reg-name"
                className="text-input"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                required
              />
            </div>
          )}
          <div className="field">
            <label htmlFor="auth-email" className="field-label">
              Email
            </label>
            <input
              id="auth-email"
              type="email"
              className="text-input"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className="field">
            <label htmlFor="auth-pw" className="field-label">
              Password
            </label>
            <input
              id="auth-pw"
              type="password"
              className="text-input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={8}
              required
            />
          </div>
          {error && <p className="error-text">{error}</p>}
          <button type="submit" disabled={submitting} className="btn btn-primary btn-block">
            {submitting ? "Please wait…" : mode === "login" ? "Log in" : "Create account"}
          </button>
        </form>

        <div className="divider" />
        <button type="button" onClick={onGuestBrowse} className="btn btn-outline btn-block">
          Browse without an account
        </button>
      </div>
    </div>
  );
}
