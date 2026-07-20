import type { HealthResponse } from "../../../shared/types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL as string;

export async function getHealth(): Promise<HealthResponse> {
  const res = await fetch(`${BASE_URL}/api/v1/health`);
  if (!res.ok) throw new Error(`Health check failed: ${res.status}`);
  return res.json();
}
