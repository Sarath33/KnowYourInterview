import type {
  HealthResponse,
  RegisterRequest,
  LoginRequest,
  AuthResponse,
  ForgotPasswordRequest,
  ResetPasswordRequest,
  ApiErrorBody,
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
