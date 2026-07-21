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
import { LogoMark, LogOutIcon } from "./components/icons";
import "./App.css";

function HealthBanner() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    getHealth()
      .then(setHealth)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)));
  }, []);

  if (error) return <p className="health-banner is-error">API unreachable: {error}</p>;
  if (!health || health.status === "UP") return null;
  return (
    <p className="health-banner is-error">
      API status: {health.status} ({health.service})
    </p>
  );
}

type View = "auth" | "browse" | "submissions" | "admin" | "library" | "payouts" | "adminPayouts";

function NavTab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={`nav-tab${active ? " is-active" : ""}`}>
      {label}
    </button>
  );
}

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();
  const [view, setView] = useState<View>("auth");
  const [selectedExperienceId, setSelectedExperienceId] = useState<string | null>(null);

  // Mirrors the design's handleAuthSubmit: land on Browse right after logging in
  // (only when we were sitting on the auth screen — don't yank someone off another
  // tab if their session just silently refreshes).
  useEffect(() => {
    if (isAuthenticated && view === "auth") setView("browse");
  }, [isAuthenticated, view]);

  const handleLogout = async () => {
    await logout();
    setSelectedExperienceId(null);
    setView("auth");
  };

  const selectExperience = (id: string) => setSelectedExperienceId(id);
  const backToBrowse = () => setSelectedExperienceId(null);

  return (
    <div className="app-shell">
      <nav className="nav-bar">
        <div className="nav-brand">
          <LogoMark />
          <span className="nav-wordmark">Know Your Interview</span>
        </div>

        {isAuthenticated && user ? (
          <div className="nav-links">
            <NavTab label="Browse" active={view === "browse"} onClick={() => setView("browse")} />
            <NavTab label="My library" active={view === "library"} onClick={() => setView("library")} />
            <NavTab label="My submissions" active={view === "submissions"} onClick={() => setView("submissions")} />
            <NavTab label="My payouts" active={view === "payouts"} onClick={() => setView("payouts")} />
            {user.isAdmin && (
              <>
                <NavTab label="Admin review" active={view === "admin"} onClick={() => setView("admin")} />
                <NavTab label="Admin payouts" active={view === "adminPayouts"} onClick={() => setView("adminPayouts")} />
              </>
            )}
            <span className="nav-user">{user.displayName}</span>
            <button type="button" onClick={handleLogout} aria-label="Log out" className="icon-btn">
              <LogOutIcon />
            </button>
          </div>
        ) : (
          <button type="button" onClick={() => setView("auth")} className="btn btn-primary">
            Log in
          </button>
        )}
      </nav>

      <main className="app-main">
        <HealthBanner />

        {selectedExperienceId ? (
          <ExperienceDetail experienceId={selectedExperienceId} onClose={backToBrowse} />
        ) : (
          <>
            {view === "auth" && !isAuthenticated && <AuthForms onGuestBrowse={() => setView("browse")} />}
            {view === "browse" && <BrowseExperiences onSelect={selectExperience} />}
            {isAuthenticated && view === "library" && <MyLibrary onSelect={selectExperience} />}
            {isAuthenticated && view === "submissions" && <SubmissionWorkspace />}
            {isAuthenticated && view === "payouts" && <MyPayouts />}
            {isAuthenticated && user?.isAdmin && view === "admin" && <AdminReviewQueue />}
            {isAuthenticated && user?.isAdmin && view === "adminPayouts" && <AdminPayouts />}
          </>
        )}
      </main>
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
