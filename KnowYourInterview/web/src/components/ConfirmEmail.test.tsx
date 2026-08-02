import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { User } from "../../../shared/types";
import { ConfirmEmail, UnverifiedEmailBanner } from "./ConfirmEmail";
import { useAuth } from "../context/AuthContext";
import * as api from "../lib/api";

vi.mock("../context/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../lib/api");

const mockedUseAuth = vi.mocked(useAuth);
const mockedApi = vi.mocked(api);

function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: "user-1",
    email: "jane@example.com",
    displayName: "Jane",
    isAdmin: false,
    emailVerified: false,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

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

/** The code is six separate boxes, so "type the code" means typing into the first one and
 * letting it spill — which is also what a real user experiences. */
async function typeCode(user: ReturnType<typeof userEvent.setup>, code: string) {
  await user.type(screen.getByLabelText("Digit 1 of 6"), code);
}

function signedIn() {
  stubAuth({ isAuthenticated: true, user: makeUser() });
}

describe("ConfirmEmail", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedApi.verifyEmail.mockReset();
    mockedApi.resendVerification.mockReset();
  });

  it("submits the typed code against the signed-in address", async () => {
    signedIn();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await typeCode(user, "481902");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    await waitFor(() =>
      expect(mockedApi.verifyEmail).toHaveBeenCalledWith({ email: "jane@example.com", code: "481902" }),
    );
    expect(await screen.findByText("Email confirmed")).toBeInTheDocument();
  });

  /** Typing one digit per box is the fallback; pasting the whole code out of the email is how
   * most people will actually do it. */
  it("spreads a pasted code across every box", async () => {
    signedIn();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await user.click(screen.getByLabelText("Digit 1 of 6"));
    await user.paste("481902");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    await waitFor(() =>
      expect(mockedApi.verifyEmail).toHaveBeenCalledWith({ email: "jane@example.com", code: "481902" }),
    );
  });

  it("ignores non-numeric characters in a pasted code", async () => {
    signedIn();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await user.click(screen.getByLabelText("Digit 1 of 6"));
    await user.paste("481-902");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    await waitFor(() =>
      expect(mockedApi.verifyEmail).toHaveBeenCalledWith({ email: "jane@example.com", code: "481902" }),
    );
  });

  it("won't submit an incomplete code", async () => {
    signedIn();
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await typeCode(user, "4819");

    expect(screen.getByRole("button", { name: "Confirm email" })).toBeDisabled();
    expect(mockedApi.verifyEmail).not.toHaveBeenCalled();
  });

  /** Every guess costs one of five, so leaving the wrong digits sitting there invites the user
   * to spend another on the same code. */
  it("clears the boxes after a rejected code so the same one isn't resubmitted", async () => {
    signedIn();
    mockedApi.verifyEmail.mockRejectedValue(new Error("That code isn't valid or has expired."));
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await typeCode(user, "000000");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    expect(await screen.findByText("That code isn't valid or has expired.")).toBeInTheDocument();
    expect(screen.getByLabelText("Digit 1 of 6")).toHaveValue("");
    expect(screen.getByRole("button", { name: "Confirm email" })).toBeDisabled();
  });

  /** emailVerified lives on the user object, so without a refresh the banner and the disabled
   * submit/unlock buttons would stay as they were until the access token happened to expire. */
  it("refreshes the session for a signed-in user so the gate lifts immediately", async () => {
    const refreshSession = vi.fn().mockResolvedValue(undefined);
    stubAuth({ isAuthenticated: true, user: makeUser(), refreshSession });
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await typeCode(user, "481902");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    await waitFor(() => expect(refreshSession).toHaveBeenCalledTimes(1));
  });

  /** The code often arrives on a different device from the one that registered, so this has to
   * work with no session — which means asking for the address, since the code alone doesn't
   * identify an account. */
  it("asks a signed-out visitor for their address and sends it with the code", async () => {
    stubAuth();
    mockedApi.verifyEmail.mockResolvedValue({ message: "Email confirmed." });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await typeCode(user, "481902");
    await user.click(screen.getByRole("button", { name: "Confirm email" }));

    await waitFor(() =>
      expect(mockedApi.verifyEmail).toHaveBeenCalledWith({ email: "jane@example.com", code: "481902" }),
    );
  });

  it("doesn't ask a signed-in user to retype their own address", () => {
    signedIn();

    render(<ConfirmEmail onContinue={vi.fn()} />);

    expect(screen.queryByLabelText("Email")).not.toBeInTheDocument();
    expect(screen.getByText("jane@example.com")).toBeInTheDocument();
  });

  it("resends a code and clears whatever was half-typed", async () => {
    signedIn();
    mockedApi.resendVerification.mockResolvedValue({ message: "sent" });
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await typeCode(user, "481");
    await user.click(screen.getByRole("button", { name: "Send a new code" }));

    await waitFor(() =>
      expect(mockedApi.resendVerification).toHaveBeenCalledWith({ email: "jane@example.com" }),
    );
    expect(await screen.findByText(/New code sent/)).toBeInTheDocument();
    expect(screen.getByLabelText("Digit 1 of 6")).toHaveValue("");
  });

  /** The resend endpoint is the most tightly rate-limited in the app (3/min) precisely because
   * it sends mail to a third party, so hitting the limit is realistic and needs to be legible. */
  it("surfaces a rate-limited resend instead of silently doing nothing", async () => {
    signedIn();
    mockedApi.resendVerification.mockRejectedValue(
      new Error("Too many attempts from this address — try again in a minute."),
    );
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Send a new code" }));

    expect(await screen.findByText(/Too many attempts/)).toBeInTheDocument();
  });

  /** Confirming is required to submit or purchase, but not to look around — so there has to be
   * a way off this screen that isn't "confirm or leave". */
  it("lets someone postpone and carry on browsing", async () => {
    signedIn();
    const onContinue = vi.fn();
    const user = userEvent.setup();

    render(<ConfirmEmail onContinue={onContinue} />);
    await user.click(screen.getByRole("button", { name: "I'll do this later" }));

    expect(onContinue).toHaveBeenCalledTimes(1);
  });
});

describe("UnverifiedEmailBanner", () => {
  it("routes to the code screen", async () => {
    const onEnterCode = vi.fn();
    const user = userEvent.setup();

    render(<UnverifiedEmailBanner email="jane@example.com" onEnterCode={onEnterCode} />);
    await user.click(screen.getByRole("button", { name: "Enter code" }));

    expect(onEnterCode).toHaveBeenCalledTimes(1);
  });

  it("names the address the code went to", () => {
    render(<UnverifiedEmailBanner email="jane@example.com" onEnterCode={vi.fn()} />);

    expect(screen.getByText(/jane@example.com/)).toBeInTheDocument();
  });
});
