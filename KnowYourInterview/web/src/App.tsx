import { useEffect, useState } from "react";
import { getHealth } from "./lib/api";
import type { HealthResponse } from "../../shared/types";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { AuthForms } from "./components/AuthForms";
import "./App.css";

function HealthBanner() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getHealth()
      .then(setHealth)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  if (error) return <p style={{ color: "red" }}>API unreachable: {error}</p>;
  if (!health) return <p>Checking API…</p>;
  return (
    <p>
      API status: <strong>{health.status}</strong> ({health.service})
    </p>
  );
}

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();

  return (
    <div style={{ fontFamily: "sans-serif", padding: "2rem" }}>
      <h1>Know Your Interview</h1>
      <HealthBanner />

      {isAuthenticated && user ? (
        <div style={{ marginTop: "1.5rem" }}>
          <p>
            Signed in as <strong>{user.displayName}</strong> ({user.email})
            {user.isAdmin && " — admin"}
          </p>
          <button type="button" onClick={() => logout()}>
            Log out
          </button>
        </div>
      ) : (
        <AuthForms />
      )}
    </div>
  );
}

function App() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}

export default App;
