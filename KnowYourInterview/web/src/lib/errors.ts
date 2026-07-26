import { ApiError } from "./api";

/** Single source of truth for turning a caught error into a user-facing string. For an
 * ApiError it prefers the first field-level validation message (falling back to the top
 * level message); otherwise it uses the Error message, or a generic fallback. Replaces the
 * copy-pasted variants that used to live in every component. */
export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    const firstFieldError = err.fieldErrors && Object.values(err.fieldErrors)[0];
    return firstFieldError ?? err.message;
  }
  return err instanceof Error ? err.message : "Something went wrong";
}
