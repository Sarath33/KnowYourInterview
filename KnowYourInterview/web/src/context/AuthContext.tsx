import { createContext, useCallback, useContext, useMemo, useState } from "react";
import type { ReactNode } from "react";
import type { User } from "../../../shared/types";
import * as api from "../lib/api";
import { clearSession, loadSession, saveSession } from "../lib/authStorage";

interface AuthContextValue {
  user: User | null;
  accessToken: string | null;
  isAuthenticated: boolean;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const initial = loadSession();
  const [user, setUser] = useState<User | null>(initial?.user ?? null);
  const [accessToken, setAccessToken] = useState<string | null>(initial?.accessToken ?? null);
  const [refreshToken, setRefreshToken] = useState<string | null>(initial?.refreshToken ?? null);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    const res = await api.register({ email, password, displayName });
    saveSession(res);
    setUser(res.user);
    setAccessToken(res.accessToken);
    setRefreshToken(res.refreshToken);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.login({ email, password });
    saveSession(res);
    setUser(res.user);
    setAccessToken(res.accessToken);
    setRefreshToken(res.refreshToken);
  }, []);

  const logout = useCallback(async () => {
    if (refreshToken) {
      try {
        await api.logout(refreshToken);
      } catch {
        // Already invalid/expired server-side — fine, we're clearing local state regardless.
      }
    }
    clearSession();
    setUser(null);
    setAccessToken(null);
    setRefreshToken(null);
  }, [refreshToken]);

  const value = useMemo<AuthContextValue>(
    () => ({ user, accessToken, isAuthenticated: !!user, register, login, logout }),
    [user, accessToken, register, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used within an AuthProvider");
  return ctx;
}
