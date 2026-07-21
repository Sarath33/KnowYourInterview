import { useEffect, useState } from "react";
import { getHealth } from "./lib/api";
import type { HealthResponse } from "../../shared/types";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { AuthForms } from "./components/AuthForms";
import { BrowseExperiences } from "./components/BrowseExperiences";
import { SubmissionWorkspace } from "./components/SubmissionWorkspace";
import { AdminReviewQueue } from "./components/AdminReviewQueue";
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

type View = "browse" | "submissions" | "admin";

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();
  const [view, setView] = useState<View>("browse");

  return (
    <div style={{ fontFamily: "sans-serif", padding: "2rem" }}>
      <h1>Know Your Interview</h1>
      <HealthBanner />

      {isAuthenticated && user ? (
        <div style={{ marginTop: "1rem" }}>
          <p>
            Signed in as <strong>{user.displayName}</strong> ({user.email})
            {user.isAdmin && " — admin"}{" "}
            <button type="button" onClick={() => logout()}>
              Log out
            </button>
          </p>

          <nav style={{ display: "flex", gap: "0.5rem", marginBottom: "0.5rem" }}>
            <button type="button" onClick={() => setView("browse")} disabled={view === "browse"}>
              Browse
            </button>
            <button type="button" onClick={() => setView("submissions")} disabled={view === "submissions"}>
              My submissions
            </button>
            {user.isAdmin && (
              <button type="button" onClick={() => setView("admin")} disabled={view === "admin"}>
                Admin review
              </button>
            )}
          </nav>

          {view === "browse" && <BrowseExperiences />}
          {view === "submissions" && <SubmissionWorkspace />}
          {view === "admin" && user.isAdmin && <AdminReviewQueue />}
        </div>
      ) : (
        <>
          <BrowseExperiences />
          <AuthForms />
        </>
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
