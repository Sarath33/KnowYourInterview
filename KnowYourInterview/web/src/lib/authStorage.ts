import type { User } from "../../../shared/types";

// MVP simplification: tokens live in localStorage. That's readable by any JS on the
// page, so it's vulnerable to XSS in a way an httpOnly cookie wouldn't be. Fine for
// getting Phase 2 working; worth revisiting (httpOnly refresh cookie + in-memory
// access token) before this handles real user data at scale.
const ACCESS_TOKEN_KEY = "kyi.accessToken";
const REFRESH_TOKEN_KEY = "kyi.refreshToken";
const USER_KEY = "kyi.user";

export interface StoredSession {
  accessToken: string;
  refreshToken: string;
  user: User;
}

export function saveSession(session: StoredSession): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, session.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, session.refreshToken);
  localStorage.setItem(USER_KEY, JSON.stringify(session.user));
}

export function loadSession(): StoredSession | null {
  const accessToken = localStorage.getItem(ACCESS_TOKEN_KEY);
  const refreshToken = localStorage.getItem(REFRESH_TOKEN_KEY);
  const userJson = localStorage.getItem(USER_KEY);
  if (!accessToken || !refreshToken || !userJson) return null;
  try {
    return { accessToken, refreshToken, user: JSON.parse(userJson) as User };
  } catch {
    return null;
  }
}

export function clearSession(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}
