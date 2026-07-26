import { useEffect, useState } from "react";
import type { ReactNode } from "react";
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

function NavTab({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className={`nav-tab${active ? " is-active" : ""}`}>
      {label}
    </button>
  );
}

/** Bounces to `to` via a replace navigation (no history entry to get stuck on) and renders
 * nothing. Replaces the imperative redirectTarget/useEffect that used to live in AppContent. */
function Redirect({ to }: { to: string }) {
  const { navigate } = useRouter();
  useEffect(() => {
    navigate(to, { replace: true });
  }, [to, navigate]);
  return null;
}

/** Route guard: renders children only for a signed-in user; otherwise bounces to /login.
 * Encodes the former AUTH_REQUIRED semantics. */
function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Redirect to="/login" />;
  return <>{children}</>;
}

/** Route guard: renders children only for a signed-in admin; non-admins go to /browse,
 * signed-out users to /login. Encodes the former ADMIN_REQUIRED semantics. */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAuthenticated, user } = useAuth();
  if (!isAuthenticated) return <Redirect to="/login" />;
  if (!user?.isAdmin) return <Redirect to="/browse" />;
  return <>{children}</>;
}

function AppContent() {
  const { user, isAuthenticated, logout } = useAuth();
  const { pathname, navigate, goBack } = useRouter();
  const route = parseRoute(pathname);

  const handleLogout = async () => {
    await logout();
    navigate("/login");
  };

  const closeDetail = () => goBack(route.name === "library" ? "/library" : "/browse");

  // Guards are expressed declaratively via <RequireAuth>/<RequireAdmin> + <Redirect>,
  // replacing the old nested-ternary redirectTarget. Redirect behavior (replace:true) and
  // the auth/admin semantics are preserved.
  const renderRoute = () => {
    switch (route.name) {
      case "redirect":
        return <Redirect to={route.to || (isAuthenticated ? "/browse" : "/login")} />;
      case "login":
        return isAuthenticated ? <Redirect to="/browse" /> : <AuthForms onGuestBrowse={() => navigate("/browse")} />;
      case "browse":
        return route.experienceId ? (
          <ExperienceDetail
            experienceId={route.experienceId}
            onClose={closeDetail}
            onLoginRequired={() => navigate("/login")}
          />
        ) : (
          <BrowseExperiences onSelect={(id) => navigate(`/browse/${id}`)} />
        );
      case "library":
        return (
          <RequireAuth>
            {route.experienceId ? (
              <ExperienceDetail
                experienceId={route.experienceId}
                onClose={closeDetail}
                onLoginRequired={() => navigate("/login")}
              />
            ) : (
              <MyLibrary onSelect={(id) => navigate(`/library/${id}`)} />
            )}
          </RequireAuth>
        );
      case "submissions":
        return (
          <RequireAuth>
            <SubmissionWorkspace />
          </RequireAuth>
        );
      case "payouts":
        return (
          <RequireAuth>
            <MyPayouts />
          </RequireAuth>
        );
      case "admin":
        return (
          <RequireAdmin>
            <AdminReviewQueue />
          </RequireAdmin>
        );
      case "adminPayouts":
        return (
          <RequireAdmin>
            <AdminPayouts />
          </RequireAdmin>
        );
    }
  };

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
        {renderRoute()}
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
