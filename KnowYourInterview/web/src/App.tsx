import { useEffect, useState } from "react";
import { getHealth } from "./lib/api";
import type { HealthResponse } from "../../shared/types";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { RouterProvider, useRouter } from "./lib/router";
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

// Every screen is a real path so the browser's Back/Forward buttons — and shared links,
// and a plain page refresh — land somewhere sensible. /browse/:id and /library/:id are
// nested under their list on purpose: it encodes which list an experience was opened from
// directly in the URL, so "back" from a detail view returns to the right list without
// needing to inspect browser history depth.
type Route =
  | { name: "login" }
  | { name: "browse"; experienceId: string | null }
  | { name: "library"; experienceId: string | null }
  | { name: "submissions" }
  | { name: "payouts" }
  | { name: "admin" }
  | { name: "adminPayouts" }
  | { name: "redirect"; to: string };

function parseRoute(pathname: string): Route {
  if (pathname === "/") return { name: "redirect", to: "" };
  if (pathname === "/login") return { name: "login" };
  const browseMatch = pathname.match(/^\/browse(?:\/([^/]+))?\/?$/);
  if (browseMatch) return { name: "browse", experienceId: browseMatch[1] ?? null };
  const libraryMatch = pathname.match(/^\/library(?:\/([^/]+))?\/?$/);
  if (libraryMatch) return { name: "library", experienceId: libraryMatch[1] ?? null };
  if (pathname === "/submissions") return { name: "submissions" };
  if (pathname === "/payouts") return { name: "payouts" };
  if (pathname === "/admin") return { name: "admin" };
  if (pathname === "/admin/payouts") return { name: "adminPayouts" };
  return { name: "redirect", to: "/browse" };
}

const AUTH_REQUIRED: Route["name"][] = ["library", "submissions", "payouts", "admin", "adminPayouts"];
const ADMIN_REQUIRED: Route["name"][] = ["admin", "adminPayouts"];

function NavTab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={`nav-tab${active ? " is-active" : ""}`}>
      {label}
    </button>
  );
}

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();
  const { pathname, navigate, goBack } = useRouter();
  const route = parseRoute(pathname);

  // A route that needs a bounce (root "/", already-logged-in on /login, an auth/admin
  // gate that isn't satisfied) never actually renders — it's redirected via `replace` so
  // the bounce itself doesn't leave a history entry to get stuck on.
  const redirectTarget =
    route.name === "redirect"
      ? route.to || (isAuthenticated ? "/browse" : "/login")
      : route.name === "login" && isAuthenticated
        ? "/browse"
        : AUTH_REQUIRED.includes(route.name) && !isAuthenticated
          ? "/login"
          : ADMIN_REQUIRED.includes(route.name) && isAuthenticated && !user?.isAdmin
            ? "/browse"
            : null;

  useEffect(() => {
    if (redirectTarget) navigate(redirectTarget, { replace: true });
  }, [redirectTarget, navigate]);

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  const closeDetail = () => goBack(route.name === "library" ? "/library" : "/browse");

  return (
    <div className="app-shell">
      <nav className="nav-bar">
        <div className="nav-brand">
          <LogoMark />
          <span className="nav-wordmark">Know Your Interview</span>
        </div>

        {isAuthenticated && user ? (
          <div className="nav-links">
            <NavTab label="Browse" active={route.name === "browse"} onClick={() => navigate("/browse")} />
            <NavTab label="My library" active={route.name === "library"} onClick={() => navigate("/library")} />
            <NavTab label="My submissions" active={route.name === "submissions"} onClick={() => navigate("/submissions")} />
            <NavTab label="My payouts" active={route.name === "payouts"} onClick={() => navigate("/payouts")} />
            {user.isAdmin && (
              <>
                <NavTab label="Admin review" active={route.name === "admin"} onClick={() => navigate("/admin")} />
                <NavTab label="Admin payouts" active={route.name === "adminPayouts"} onClick={() => navigate("/admin/payouts")} />
              </>
            )}
            <span className="nav-user">{user.displayName}</span>
            <button type="button" onClick={handleLogout} aria-label="Log out" className="icon-btn">
              <LogOutIcon />
            </button>
          </div>
        ) : (
          <button type="button" onClick={() => navigate("/login")} className="btn btn-primary">
            Log in
          </button>
        )}
      </nav>

      <main className="app-main">
        <HealthBanner />

        {redirectTarget ? null : (
          <>
            {(route.name === "browse" || route.name === "library") && route.experienceId ? (
              <ExperienceDetail
                experienceId={route.experienceId}
                onClose={closeDetail}
                onLoginRequired={() => navigate("/login")}
              />
            ) : (
              <>
                {route.name === "login" && <AuthForms onGuestBrowse={() => navigate("/browse")} />}
                {route.name === "browse" && <BrowseExperiences onSelect={(id) => navigate(`/browse/${id}`)} />}
                {route.name === "library" && <MyLibrary onSelect={(id) => navigate(`/library/${id}`)} />}
                {route.name === "submissions" && <SubmissionWorkspace />}
                {route.name === "payouts" && <MyPayouts />}
                {route.name === "admin" && <AdminReviewQueue />}
                {route.name === "adminPayouts" && <AdminPayouts />}
              </>
            )}
          </>
        )}
      </main>
    </div>
  );
}

function App() {
  return (
    <AuthProvider>
      <RouterProvider>
        <AppContent />
      </RouterProvider>
    </AuthProvider>
  );
}

export default App;
