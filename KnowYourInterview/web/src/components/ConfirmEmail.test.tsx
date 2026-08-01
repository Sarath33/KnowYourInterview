import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConfirmEmail, UnverifiedEmailBanner } from "./ConfirmEmail";
import { useAuth } from "../context/AuthContext";
import * as api from "../lib/api";

vi.mock("../context/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../lib/api");

const mockedUseAuth = vi.mocked(useAuth);
const mockedApi = vi.mocked(api);

function stubAuth(overrides: Partial<ReturnType<typeof useAuth>> = {}) {
  mockedUseAuth.mockReturnValue({
    user: null,
    accessToken: null,
    isAuthenticated: false,
    register: vi.fn(),
    login: vi.fn(),
    googleLogin: vi.fn(),
    logout: vi.fn(),
    refreshSession: vi.fn(),
    ...overrides,
  });
}

describe("ConfirmEmail", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedApi.verifyEmail.mockReset();
  });

  it("redeems the token on mount without needing a button press", async () => {
    stubAuth();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });

    render(<ConfirmEmail token="raw-token" onContinue={vi.fn()} />);

    await waitFor(() => expect(mockedApi.verifyEmail).toHaveBeenCalledWith({ token: "raw-token" }));
    expect(await screen.findByText("Email confirmed")).toBeInTheDocument();
  });

  /** The token is single-use, so a double-invoked effect (StrictMode in development) must not
   * fire two requests — the second would race the first for a token only one can spend. */
  it("only attempts the token once even if the effect re-runs", async () => {
    stubAuth();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });

    const { rerender } = render(<ConfirmEmail token="raw-token" onContinue={vi.fn()} />);
    rerender(<ConfirmEmail token="raw-token" onContinue={vi.fn()} />);

    await screen.findByText("Email confirmed");
    expect(mockedApi.verifyEmail).toHaveBeenCalledTimes(1);
  });

  /** emailVerified lives on the user object, so without a refresh the banner and the disabled
   * submit/unlock buttons would stay as they were until the access token happened to expire. */
  it("refreshes the session for a signed-in user so the gate lifts immediately", async () => {
    const refreshSession = vi.fn().mockResolvedValue(undefined);
    stubAuth({ isAuthenticated: true, refreshSession });
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });

    render(<ConfirmEmail token="raw-token" onContinue={vi.fn()} />);

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
  });

  it("doesn't try to refresh a session that doesn't exist", async () => {
    const refreshSession = vi.fn();
    stubAuth({ isAuthenticated: false, refreshSession });
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });

    render(<ConfirmEmail token="raw-token" onContinue={vi.fn()} />);

    await screen.findByText("Email confirmed");
    expect(refreshSession).not.toHaveBeenCalled();
  });

  it("explains an expired or reused link and points at the resend path", async () => {
    stubAuth();
    mockedApi.verifyEmail.mockRejectedValue(new Error("That confirmation link has expired."));

    render(<ConfirmEmail token="stale-token" onContinue={vi.fn()} />);

    expect(await screen.findByText("We couldn't confirm that link")).toBeInTheDocument();
    expect(screen.getByText("That confirmation link has expired.")).toBeInTheDocument();
  });

  it("handles a link with no token at all without calling the API", async () => {
    stubAuth();

    render(<ConfirmEmail token={null} onContinue={vi.fn()} />);

    expect(screen.getByText("That link looks incomplete")).toBeInTheDocument();
    expect(mockedApi.verifyEmail).not.toHaveBeenCalled();
  });
});

describe("UnverifiedEmailBanner", () => {
  beforeEach(() => {
    mockedApi.resendVerification.mockReset();
  });

  it("resends to the signed-in address and confirms it went", async () => {
    mockedApi.resendVerification.mockResolvedValue({ message: "If that account exists…" });
    const user = userEvent.setup();

    render(<UnverifiedEmailBanner email="jane@example.com" />);
    await user.click(screen.getByRole("button", { name: "Resend link" }));

    await waitFor(() =>
      expect(mockedApi.resendVerification).toHaveBeenCalledWith({ email: "jane@example.com" }),
    );
    expect(await screen.findByText(/Sent — check jane@example.com/)).toBeInTheDocument();
  });

  /** The resend endpoint is the most tightly rate-limited in the app (3/min) precisely because
   * it sends mail to a third party, so hitting that limit is a realistic outcome and needs to
   * be legible rather than silent. */
  it("surfaces a rate-limit rejection instead of silently doing nothing", async () => {
    mockedApi.resendVerification.mockRejectedValue(
      new Error("Too many attempts from this address — try again in a minute."),
    );
    const user = userEvent.setup();

    render(<UnverifiedEmailBanner email="jane@example.com" />);
    await user.click(screen.getByRole("button", { name: "Resend link" }));

    expect(await screen.findByText(/Too many attempts/)).toBeInTheDocument();
  });

  it("hides the resend button once a link has been sent", async () => {
    mockedApi.resendVerification.mockResolvedValue({ message: "ok" });
    const user = userEvent.setup();

    render(<UnverifiedEmailBanner email="jane@example.com" />);
    await user.click(screen.getByRole("button", { name: "Resend link" }));

    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Resend link" })).not.toBeInTheDocument(),
    );
  });
});
