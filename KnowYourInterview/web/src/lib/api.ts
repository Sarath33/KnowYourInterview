import type {
  HealthResponse,
  RegisterRequest,
  LoginRequest,
  GoogleLoginRequest,
  AuthResponse,
  ApiErrorBody,
  ForgotPasswordRequest,
  ResetPasswordRequest,
  VerifyEmailRequest,
  ResendVerificationRequest,
  MessageResponse,
  ExperienceRequest,
  RoundRequest,
  RejectRequest,
  CorrectionRequest,
  ExperienceFull,
  ExperienceRound,
  ExperienceEditSnapshot,
  ExperienceTeaser,
  ExperienceView,
  ProofDocument,
  PagedResponse,
  CreateOrderResponse,
  ConfirmPaymentRequest,
  Purchase,
  Payout,
  PayoutAdminView,
  MarkPayoutPaidRequest,
  ProfileResponse,
  UpdateProfileRequest,
  ChangeEmailRequest,
  ChangePasswordRequest,
  PayoutAccount,
  PayoutAccountRequest,
  DeleteAccountRequest,
  User,
} from "../../../shared/types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL as string;

export class ApiError extends Error {
  status: number;
  fieldErrors?: Record<string, string>;

  constructor(body: ApiErrorBody, status: number) {
    super(body.message ?? `Request failed: ${status}`);
    this.status = status;
    this.fieldErrors = body.fieldErrors;
  }
}

// --- Auth bridge ---------------------------------------------------------
// Rather than drilling `token` through every component and api call, the api layer
// pulls the current access token from an accessor the AuthProvider registers. It also
// gets a way to read the refresh token and hand back refreshed sessions so request()
// can transparently recover from an expired access token (single-flight 401 refresh).

let getAccessToken: () => string | null = () => null;
let getRefreshToken: () => string | null = () => null;
let onRefreshSuccess: (res: AuthResponse) => void = () => {};
let onAuthFailure: () => void = () => {};

export function setAuthTokenGetter(fn: () => string | null): void {
  getAccessToken = fn;
}

export function setAuthHandlers(handlers: {
  getRefreshToken: () => string | null;
  onRefreshSuccess: (res: AuthResponse) => void;
  onAuthFailure: () => void;
}): void {
  getRefreshToken = handlers.getRefreshToken;
  onRefreshSuccess = handlers.onRefreshSuccess;
  onAuthFailure = handlers.onAuthFailure;
}

/** Turns a non-ok Response into a thrown ApiError, or a plain Error when the body isn't
 * the JSON error envelope we expect. Centralises the "res.json() might throw" guard so
 * request(), uploadProof(), and openProof() all fail the same, safe way. */
async function throwFromErrorResponse(res: Response, fallbackMessage?: string): Promise<never> {
  let body: ApiErrorBody;
  try {
    body = await res.json();
  } catch {
    throw new Error(fallbackMessage ?? `Request failed: ${res.status}`);
  }
  throw new ApiError(body, res.status);
}

// Dedupe concurrent 401s so only a single refresh call ever runs at a time; everyone
// else awaits the same in-flight promise.
let refreshInFlight: Promise<AuthResponse> | null = null;

async function attemptRefresh(): Promise<AuthResponse | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return null;
  if (!refreshInFlight) {
    refreshInFlight = refreshTokens(refreshToken)
      .then((res) => {
        onRefreshSuccess(res);
        return res;
      })
      .finally(() => {
        refreshInFlight = null;
      });
  }
  try {
    return await refreshInFlight;
  } catch {
    return null;
  }
}

function fetchWithAuth(path: string, options: RequestInit, token: string | null): Promise<Response> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...(options.headers as Record<string, string> | undefined),
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(`${BASE_URL}${path}`, { ...options, headers });
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  config: { skipAuthRefresh?: boolean } = {},
): Promise<T> {
  let res = await fetchWithAuth(path, options, getAccessToken());

  // On a 401, try to recover once: refresh the session (single-flight) and replay the
  // original request with the fresh access token. If refresh fails, tear the session down
  // and bounce to /login.
  if (res.status === 401 && !config.skipAuthRefresh && getRefreshToken()) {
    const refreshed = await attemptRefresh();
    if (refreshed) {
      res = await fetchWithAuth(path, options, refreshed.accessToken);
    } else {
      onAuthFailure();
      if (typeof window !== "undefined") window.location.href = "/login";
      await throwFromErrorResponse(res);
    }
  }

  if (!res.ok) {
    await throwFromErrorResponse(res);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

export async function getHealth(): Promise<HealthResponse> {
  return request<HealthResponse>("/api/v1/health");
}

export async function register(body: RegisterRequest): Promise<AuthResponse> {
  return request<AuthResponse>(
    "/api/v1/auth/register",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

export async function login(body: LoginRequest): Promise<AuthResponse> {
  return request<AuthResponse>(
    "/api/v1/auth/login",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

export async function googleLogin(body: GoogleLoginRequest): Promise<AuthResponse> {
  return request<AuthResponse>(
    "/api/v1/auth/google",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

export async function refreshTokens(refreshToken: string): Promise<AuthResponse> {
  return request<AuthResponse>(
    "/api/v1/auth/refresh",
    { method: "POST", body: JSON.stringify({ refreshToken }) },
    { skipAuthRefresh: true },
  );
}

export async function logout(refreshToken: string): Promise<void> {
  return request<void>(
    "/api/v1/auth/logout",
    { method: "POST", body: JSON.stringify({ refreshToken }) },
    { skipAuthRefresh: true },
  );
}

/**
 * Asks for a reset link. Always resolves for a well-formed email, whether or not an account
 * exists — the backend deliberately gives no user-enumeration signal, so the caller must not
 * present the response as confirmation that the address is registered.
 *
 * Note that no email is actually sent yet: AuthService#forgotPassword logs the link
 * server-side instead (no email provider is wired up). The UI copy reflects that.
 */
export async function forgotPassword(body: ForgotPasswordRequest): Promise<MessageResponse> {
  return request<MessageResponse>(
    "/api/v1/auth/forgot-password",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

/** Consumes the single-use token from a reset link and sets a new password. 401 if the token
 * is unknown, already used, or expired. skipAuthRefresh like the other auth calls — this runs
 * for a signed-out user, so a 401 here means "bad token", not "session expired". */
export async function resetPassword(body: ResetPasswordRequest): Promise<MessageResponse> {
  return request<MessageResponse>(
    "/api/v1/auth/reset-password",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

/** Checks a confirmation code. skipAuthRefresh matters here: the person typing the code often
 * isn't signed in on the device that received it, and a 401 from this endpoint means "wrong or
 * expired code" — letting the generic handler treat it as an expired session would bounce them
 * to /login mid-confirmation. */
export async function verifyEmail(body: VerifyEmailRequest): Promise<MessageResponse> {
  return request<MessageResponse>(
    "/api/v1/auth/verify-email",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

/** Sends a fresh confirmation code and invalidates any earlier one. Resolves the same way for
 * any address — an unknown or already-confirmed one included — so don't present the result as
 * proof the account exists. */
export async function resendVerification(body: ResendVerificationRequest): Promise<MessageResponse> {
  return request<MessageResponse>(
    "/api/v1/auth/resend-verification",
    { method: "POST", body: JSON.stringify(body) },
    { skipAuthRefresh: true },
  );
}

// --- Experiences (contributor) ---

export async function createExperience(body: ExperienceRequest): Promise<ExperienceFull> {
  return request("/api/v1/experiences", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function updateExperience(id: string, body: ExperienceRequest): Promise<ExperienceFull> {
  return request(`/api/v1/experiences/${id}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export async function getEditHistory(id: string): Promise<ExperienceEditSnapshot[]> {
  return request(`/api/v1/experiences/${id}/history`);
}

export async function addRound(id: string, body: RoundRequest): Promise<ExperienceRound> {
  return request(`/api/v1/experiences/${id}/rounds`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function updateRound(id: string, roundId: string, body: RoundRequest): Promise<ExperienceRound> {
  return request(`/api/v1/experiences/${id}/rounds/${roundId}`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export async function deleteRound(id: string, roundId: string): Promise<void> {
  return request(`/api/v1/experiences/${id}/rounds/${roundId}`, {
    method: "DELETE",
  });
}

export async function uploadProof(id: string, file: File): Promise<ProofDocument> {
  const form = new FormData();
  form.append("file", file);
  const token = getAccessToken();
  const res = await fetch(`${BASE_URL}/api/v1/experiences/${id}/proof`, {
    method: "POST",
    // no Content-Type — the browser sets the multipart boundary
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
    body: form,
  });
  if (!res.ok) {
    await throwFromErrorResponse(res);
  }
  return res.json();
}

export async function submitExperience(id: string): Promise<ExperienceFull> {
  return request(`/api/v1/experiences/${id}/submit`, {
    method: "POST",
  });
}

export async function listMyExperiences(): Promise<ExperienceFull[]> {
  return request("/api/v1/experiences/mine");
}

export async function deleteProofDocument(id: string, proofId: string): Promise<void> {
  return request(`/api/v1/experiences/${id}/proof/${proofId}`, {
    method: "DELETE",
  });
}

/** Draft or rejected only — the API rejects this for any other status. */
export async function deleteExperience(id: string): Promise<void> {
  return request(`/api/v1/experiences/${id}`, {
    method: "DELETE",
  });
}

/** Owner or admin — pulls a published experience back to draft so it can be edited and
 * resubmitted through review. */
export async function unpublishExperience(id: string): Promise<ExperienceFull> {
  return request(`/api/v1/experiences/${id}/unpublish`, {
    method: "POST",
  });
}

// --- Experiences (public browse) ---

// These endpoints work for guests. A signed-in access token (attached automatically when
// present) lets the backend flag which results the viewer has already unlocked.
export async function browseExperiences(params: {
  company?: string;
  roleTitle?: string;
  level?: string;
  year?: number;
  isFree?: boolean;
  search?: string;
  sort?: "newest" | "priceLow" | "priceHigh" | "mostViewed";
  page?: number;
  size?: number;
}): Promise<PagedResponse<ExperienceTeaser>> {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => {
    if (v !== undefined && v !== "") query.set(k, String(v));
  });
  const qs = query.toString();
  return request(`/api/v1/experiences${qs ? `?${qs}` : ""}`);
}

export async function getExperience(id: string): Promise<ExperienceView> {
  return request(`/api/v1/experiences/${id}`);
}

/**
 * Opens a proof document in a new tab. A plain <a href> can't carry the
 * Authorization header the endpoint requires (owner-or-admin gated), so this
 * fetches it as a blob and opens an object URL instead.
 */
export async function openProof(experienceId: string, proofId: string): Promise<void> {
  const token = getAccessToken();
  const res = await fetch(`${BASE_URL}/api/v1/experiences/${experienceId}/proof/${proofId}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  });
  if (!res.ok) {
    await throwFromErrorResponse(res, `Failed to load proof document: ${res.status}`);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  window.open(url, "_blank");
  // Revoke after a delay rather than immediately — the new tab needs the URL to
  // still be valid by the time it finishes loading.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// --- Payments ---

export async function createPurchaseOrder(experienceId: string): Promise<CreateOrderResponse> {
  return request(`/api/v1/experiences/${experienceId}/purchase`, {
    method: "POST",
  });
}

export async function confirmPurchase(body: ConfirmPaymentRequest): Promise<Purchase> {
  return request("/api/v1/purchases/confirm", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function listMyPurchases(): Promise<Purchase[]> {
  return request("/api/v1/purchases/mine");
}

// --- Admin review ---

export async function adminReviewQueue(): Promise<ExperienceFull[]> {
  return request("/api/v1/admin/experiences");
}

export async function adminApprove(id: string): Promise<ExperienceFull> {
  return request(`/api/v1/admin/experiences/${id}/approve`, {
    method: "POST",
  });
}

export async function adminReject(id: string, body: RejectRequest): Promise<ExperienceFull> {
  return request(`/api/v1/admin/experiences/${id}/reject`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** The softer alternative to reject — see CorrectionRequest and ExperienceStatus's
 * "CORRECTION_REQUESTED" value. Typically called right after an admin edit
 * (updateExperience) fixing the submission directly. */
export async function adminRequestCorrection(id: string, body: CorrectionRequest): Promise<ExperienceFull> {
  return request(`/api/v1/admin/experiences/${id}/request-correction`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

// --- Payouts ---

export async function adminPayoutQueue(): Promise<PayoutAdminView[]> {
  return request("/api/v1/admin/payouts");
}

export async function adminMarkPayoutPaid(id: string, body: MarkPayoutPaidRequest): Promise<PayoutAdminView> {
  return request(`/api/v1/admin/payouts/${id}/mark-paid`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function listMyPayouts(): Promise<Payout[]> {
  return request("/api/v1/payouts/mine");
}

// --- Profile / account ---

export async function getProfile(): Promise<ProfileResponse> {
  return request("/api/v1/profile");
}

export async function updateDisplayName(body: UpdateProfileRequest): Promise<User> {
  return request("/api/v1/profile", {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

/** Kicks off an email change: the backend sends a verification link to the new address and
 * only swaps it in once that's clicked, so the returned message is a "check your inbox", not
 * a done. */
export async function changeEmail(body: ChangeEmailRequest): Promise<MessageResponse> {
  return request("/api/v1/profile/change-email", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/** `currentPassword` is omitted for a Google-only account setting its first password. */
export async function changePassword(body: ChangePasswordRequest): Promise<MessageResponse> {
  return request("/api/v1/profile/change-password", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function savePayoutAccount(body: PayoutAccountRequest): Promise<PayoutAccount> {
  return request("/api/v1/profile/payout-account", {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

/** Irreversible. `password` confirms the delete for a password account; a Google-only account
 * omits it (the UI confirms by typing DELETE instead). Returns 204. */
export async function deleteAccount(body: DeleteAccountRequest): Promise<void> {
  return request("/api/v1/profile", {
    method: "DELETE",
    body: JSON.stringify(body),
  });
}
