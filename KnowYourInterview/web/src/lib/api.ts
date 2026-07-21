import type {
  HealthResponse,
  RegisterRequest,
  LoginRequest,
  AuthResponse,
  ForgotPasswordRequest,
  ResetPasswordRequest,
  ApiErrorBody,
  ExperienceRequest,
  RoundRequest,
  RejectRequest,
  ExperienceFull,
  ExperienceRound,
  ExperienceTeaser,
  ExperienceView,
  ProofDocument,
  PagedResponse,
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

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...options,
    headers: { "Content-Type": "application/json", ...options.headers },
  });

  if (!res.ok) {
    let body: ApiErrorBody;
    try {
      body = await res.json();
    } catch {
      throw new Error(`Request failed: ${res.status}`);
    }
    throw new ApiError(body, res.status);
  }

  if (res.status === 204) return undefined as T;
  return res.json();
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

export async function getHealth(): Promise<HealthResponse> {
  return request<HealthResponse>("/api/v1/health");
}

export async function register(body: RegisterRequest): Promise<AuthResponse> {
  return request<AuthResponse>("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function login(body: LoginRequest): Promise<AuthResponse> {
  return request<AuthResponse>("/api/v1/auth/login", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function refreshTokens(refreshToken: string): Promise<AuthResponse> {
  return request<AuthResponse>("/api/v1/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
}

export async function logout(refreshToken: string): Promise<void> {
  return request<void>("/api/v1/auth/logout", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
}

export async function forgotPassword(body: ForgotPasswordRequest): Promise<{ message: string }> {
  return request("/api/v1/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function resetPassword(body: ResetPasswordRequest): Promise<{ message: string }> {
  return request("/api/v1/auth/reset-password", {
    method: "POST",
    body: JSON.stringify(body),
  });
}

// --- Experiences (contributor) ---

export async function createExperience(token: string, body: ExperienceRequest): Promise<ExperienceFull> {
  return request("/api/v1/experiences", {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
}

export async function updateExperience(
  token: string,
  id: string,
  body: ExperienceRequest,
): Promise<ExperienceFull> {
  return request(`/api/v1/experiences/${id}`, {
    method: "PUT",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
}

export async function addRound(token: string, id: string, body: RoundRequest): Promise<ExperienceRound> {
  return request(`/api/v1/experiences/${id}/rounds`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
}

export async function deleteRound(token: string, id: string, roundId: string): Promise<void> {
  return request(`/api/v1/experiences/${id}/rounds/${roundId}`, {
    method: "DELETE",
    headers: authHeaders(token),
  });
}

export async function uploadProof(token: string, id: string, file: File): Promise<ProofDocument> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch(`${BASE_URL}/api/v1/experiences/${id}/proof`, {
    method: "POST",
    headers: authHeaders(token), // no Content-Type — the browser sets the multipart boundary
    body: form,
  });
  if (!res.ok) {
    const body = await res.json();
    throw new ApiError(body, res.status);
  }
  return res.json();
}

export async function submitExperience(token: string, id: string): Promise<ExperienceFull> {
  return request(`/api/v1/experiences/${id}/submit`, {
    method: "POST",
    headers: authHeaders(token),
  });
}

export async function listMyExperiences(token: string): Promise<ExperienceFull[]> {
  return request("/api/v1/experiences/mine", { headers: authHeaders(token) });
}

// --- Experiences (public browse) ---

export async function browseExperiences(params: {
  company?: string;
  roleTitle?: string;
  level?: string;
  year?: number;
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

export async function getExperience(id: string, token?: string): Promise<ExperienceView> {
  return request(`/api/v1/experiences/${id}`, token ? { headers: authHeaders(token) } : {});
}

/**
 * Opens a proof document in a new tab. A plain <a href> can't carry the
 * Authorization header the endpoint requires (owner-or-admin gated), so this
 * fetches it as a blob and opens an object URL instead.
 */
export async function openProof(token: string, experienceId: string, proofId: string): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/v1/experiences/${experienceId}/proof/${proofId}`, {
    headers: authHeaders(token),
  });
  if (!res.ok) {
    let body: ApiErrorBody;
    try {
      body = await res.json();
    } catch {
      throw new Error(`Failed to load proof document: ${res.status}`);
    }
    throw new ApiError(body, res.status);
  }
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  window.open(url, "_blank");
  // Revoke after a delay rather than immediately — the new tab needs the URL to
  // still be valid by the time it finishes loading.
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}

// --- Admin review ---

export async function adminReviewQueue(token: string): Promise<ExperienceFull[]> {
  return request("/api/v1/admin/experiences", { headers: authHeaders(token) });
}

export async function adminApprove(token: string, id: string): Promise<ExperienceFull> {
  return request(`/api/v1/admin/experiences/${id}/approve`, {
    method: "POST",
    headers: authHeaders(token),
  });
}

export async function adminReject(token: string, id: string, body: RejectRequest): Promise<ExperienceFull> {
  return request(`/api/v1/admin/experiences/${id}/reject`, {
    method: "POST",
    headers: authHeaders(token),
    body: JSON.stringify(body),
  });
}
