import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";
import type { AuthResponse, User } from "../../../shared/types";
import * as api from "../lib/api";
import { clearSession, loadSession, saveSession } from "../lib/authStorage";

interface AuthContextValue {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  googleLogin: (idToken: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Pulls a fresh session (and therefore a fresh `user`) from the server. Used after
   * confirming an email address, where the change that matters — `emailVerified` — lives on
   * the user object and would otherwise stay stale until the access token happened to expire.
   * No-ops when signed out, and swallows failures: this is a nicety on top of an action that
   * already succeeded server-side, so it must never surface as an error for it. */
  refreshSession: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

/** Base64url-decodes a JWT payload and returns its `exp` (seconds since epoch), or null
 * if the token isn't a decodable JWT. No dependency — the token is already trusted-ish
 * local state; this is only a client-side "is it obviously dead?" check, not verification. */
function decodeJwtExp(token: string): number | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    const json = JSON.parse(atob(payload.replace(/-/g, "+").replace(/_/g, "/"))) as { exp?: number };
    return typeof json.exp === "number" ? json.exp : null;
  } catch {
    return null;
  }
}

function isAccessTokenExpired(token: string): boolean {
  const exp = decodeJwtExp(token);
  if (exp == null) return false; // Can't tell — don't force a logout on an opaque token.
  return exp * 1000 <= Date.now();
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const initial = loadSession();
  // Don't trust a stale localStorage session whose access token has already expired: treat
  // it as logged-out up front (a refresh is attempted below) so isAuthenticated can't stay
  // true behind a dead token.
  const initialValid = !!initial && !isAccessTokenExpired(initial.accessToken);

  const [user, setUser] = useState<User | null>(initialValid ? initial!.user : null);
  const [accessToken, setAccessToken] = useState<string | null>(initialValid ? initial!.accessToken : null);
  const [refreshToken, setRefreshToken] = useState<string | null>(initial?.refreshToken ?? null);

  const applyTokens = useCallback((res: AuthResponse) => {
    saveSession(res);
    setUser(res.user);
    setAccessToken(res.accessToken);
    setRefreshToken(res.refreshToken);
  }, []);

  const clear = useCallback(() => {
    clearSession();
    setUser(null);
    setAccessToken(null);
    setRefreshToken(null);
  }, []);

  // Register the api layer's auth accessors synchronously (during render, via refs) so
  // that requests fired from children's mount effects already see the current token and
  // can refresh on a 401. Refs avoid stale closures without re-registering churn.
  const accessTokenRef = useRef(accessToken);
  accessTokenRef.current = accessToken;
  const refreshTokenRef = useRef(refreshToken);
  refreshTokenRef.current = refreshToken;

  api.setAuthTokenGetter(() => accessTokenRef.current);
  api.setAuthHandlers({
    getRefreshToken: () => refreshTokenRef.current,
    onRefreshSuccess: applyTokens,
    onAuthFailure: clear,
  });

  // On boot, if the persisted access token was expired, try a one-shot refresh so a user
  // returning to a still-valid refresh token stays logged in; otherwise drop the session.
  useEffect(() => {
    if (initial && !initialValid) {
      if (initial.refreshToken) {
        api
          .refreshTokens(initial.refreshToken)
          .then(applyTokens)
          .catch(() => clear());
      } else {
        clear();
      }
    }
    // Run once on mount only.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const register = useCallback(
    async (email: string, password: string, displayName: string) => {
      applyTokens(await api.register({ email, password, displayName }));
    },
    [applyTokens],
  );

  const login = useCallback(
    async (email: string, password: string) => {
      applyTokens(await api.login({ email, password }));
    },
    [applyTokens],
  );

  const googleLogin = useCallback(
    async (idToken: string) => {
      applyTokens(await api.googleLogin({ idToken }));
    },
    [applyTokens],
  );

  const refreshSession = useCallback(async () => {
    const currentRefreshToken = refreshTokenRef.current;
    if (!currentRefreshToken) return;
    try {
      applyTokens(await api.refreshTokens(currentRefreshToken));
    } catch {
      // Refresh tokens are single-use and rotate, so a concurrent refresh elsewhere can
      // legitimately lose this race. Not worth surfacing — the stale `user` corrects itself
      // on the next successful refresh, and the server-side gate doesn't depend on it.
    }
  }, [applyTokens]);

  const logout = useCallback(async () => {
    if (refreshToken) {
      try {
        await api.logout(refreshToken);
      } catch {
        // Already invalid/expired server-side — fine, we're clearing local state regardless.
      }
    }
    clear();
  }, [refreshToken, clear]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      accessToken,
      isAuthenticated: !!user,
      register,
      login,
      googleLogin,
      logout,
      refreshSession,
    }),
    [user, accessToken, register, login, googleLogin, logout, refreshSession],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within an AuthProvider");
  return ctx;
}
