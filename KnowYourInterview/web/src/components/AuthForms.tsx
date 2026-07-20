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

export function AuthForms() {
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
    <div style={{ maxWidth: 320, marginTop: "1.5rem" }}>
      <div style={{ marginBottom: "1rem" }}>
        <button type="button" onClick={() => setMode("login")} disabled={mode === "login"}>
          Log in
        </button>{" "}
        <button type="button" onClick={() => setMode("register")} disabled={mode === "register"}>
          Register
        </button>
      </div>

      <form onSubmit={handleSubmit} style={{ display: "flex", flexDirection: "column", gap: "0.5rem" }}>
        {mode === "register" && (
          <input
            type="text"
            placeholder="Display name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            required
          />
        )}
        <input
          type="email"
          placeholder="Email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <input
          type="password"
          placeholder="Password (min 8 characters)"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          minLength={8}
          required
        />
        <button type="submit" disabled={submitting}>
          {submitting ? "Please wait…" : mode === "login" ? "Log in" : "Create account"}
        </button>
        {error && <p style={{ color: "red", margin: 0 }}>{error}</p>}
      </form>
    </div>
  );
}
