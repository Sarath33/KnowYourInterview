import { useEffect, useState } from "react";
import { getHealth } from "./lib/api";
import type { HealthResponse } from "../../shared/types";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { AuthForms } from "./components/AuthForms";
import { BrowseExperiences } from "./components/BrowseExperiences";
import { SubmissionWorkspace } from "./components/SubmissionWorkspace";
import { AdminReviewQueue } from "./components/AdminReviewQueue";
import { ExperienceDetail } from "./components/ExperienceDetail";
import { MyLibrary } from "./components/MyLibrary";
import { MyPayouts } from "./components/MyPayouts";
import { AdminPayouts } from "./components/AdminPayouts";
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

type View = "browse" | "submissions" | "admin" | "library" | "payouts" | "adminPayouts";

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();
  const [view, setView] = useState<View>("browse");
  const [selectedExperienceId, setSelectedExperienceId] = useState<string | null>(null);

  if (selectedExperienceId) {
    return (
      <div style={{ fontFamily: "sans-serif", padding: "2rem" }}>
        <h1>Know Your Interview</h1>
        <ExperienceDetail
          experienceId={selectedExperienceId}
          onClose={() => setSelectedExperienceId(null)}
        />
      </div>
    );
  }

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
            <button type="button" onClick={() => setView("library")} disabled={view === "library"}>
              My library
            </button>
            <button type="button" onClick={() => setView("submissions")} disabled={view === "submissions"}>
              My submissions
            </button>
            <button type="button" onClick={() => setView("payouts")} disabled={view === "payouts"}>
              My payouts
            </button>
            {user.isAdmin && (
              <>
                <button type="button" onClick={() => setView("admin")} disabled={view === "admin"}>
                  Admin review
                </button>
                <button type="button" onClick={() => setView("adminPayouts")} disabled={view === "adminPayouts"}>
                  Admin payouts
                </button>
              </>
            )}
          </nav>

          {view === "browse" && <BrowseExperiences onSelect={setSelectedExperienceId} />}
          {view === "library" && <MyLibrary onSelect={setSelectedExperienceId} />}
          {view === "submissions" && <SubmissionWorkspace />}
          {view === "payouts" && <MyPayouts />}
          {view === "admin" && user.isAdmin && <AdminReviewQueue />}
          {view === "adminPayouts" && user.isAdmin && <AdminPayouts />}
        </div>
      ) : (
        <>
          <BrowseExperiences onSelect={setSelectedExperienceId} />
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
