import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthForms } from "./AuthForms";
import { useAuth } from "../context/AuthContext";

// AuthForms only reads `login`/`register`/`googleLogin` off useAuth(), but the hook's
// return type requires the full shape — mocking the whole module keeps the test focused
// on AuthForms' own logic (mode switching, submit wiring, error rendering) without
// needing a real AuthProvider or network calls.
vi.mock("../context/AuthContext", () => ({
  useAuth: vi.fn(),
}));

const mockedUseAuth = vi.mocked(useAuth);

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

/**
 * In login mode, both the "Log in"/"Register" mode toggle and the submit button
 * are labeled "Log in" — getByRole would find two matches, so this picks the
 * type="submit" one specifically.
 */
function getLoginSubmitButton(): HTMLElement {
  const candidates = screen.getAllByRole("button", { name: "Log in" });
  const submit = candidates.find((el) => el.getAttribute("type") === "submit");
  if (!submit) throw new Error("Could not find the Log in submit button");
  return submit;
}

describe("AuthForms", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
  });

  it("renders the login form by default and submits email/password via login()", async () => {
    const login = vi.fn().mockResolvedValue(undefined);
    stubAuth({ login });
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "hunter22");
    await user.click(getLoginSubmitButton());

    await waitFor(() => expect(login).toHaveBeenCalledWith("jane@example.com", "hunter22"));
  });

  /** Registering leaves the account unconfirmed with a code already sent, so the useful next
   * screen is the one that takes the code — logging in shouldn't divert anywhere. */
  it("hands off to the confirmation screen after registering, but not after logging in", async () => {
    const onRegistered = vi.fn();
    stubAuth({ register: vi.fn().mockResolvedValue(undefined), login: vi.fn().mockResolvedValue(undefined) });
    const user = userEvent.setup();

    const { unmount } = render(
      <AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={onRegistered} />,
    );
    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "hunter22");
    await user.click(getLoginSubmitButton());
    await waitFor(() => expect(onRegistered).not.toHaveBeenCalled());
    unmount();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={onRegistered} />);
    await user.click(screen.getByRole("button", { name: "Register" }));
    await user.type(screen.getByLabelText("Display name"), "Jane Doe");
    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "hunter22");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(onRegistered).toHaveBeenCalledTimes(1));
  });

  it("switches to register mode, shows the display name field, and submits via register()", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    stubAuth({ register });
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Register" }));
    expect(screen.getByLabelText("Display name")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Display name"), "Jane Doe");
    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "hunter22");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() =>
      expect(register).toHaveBeenCalledWith("jane@example.com", "hunter22", "Jane Doe"),
    );
  });

  it("shows an error message when login fails and does not call onGuestBrowse", async () => {
    const login = vi.fn().mockRejectedValue(new Error("Invalid email or password"));
    stubAuth({ login });
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "wrongpassword");
    await user.click(getLoginSubmitButton());

    expect(await screen.findByText("Invalid email or password")).toBeInTheDocument();
  });

  it("calls onGuestBrowse when the guest browse button is clicked", async () => {
    stubAuth();
    const onGuestBrowse = vi.fn();
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={onGuestBrowse} onForgotPassword={vi.fn()} onRegistered={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Browse without an account" }));

    expect(onGuestBrowse).toHaveBeenCalledTimes(1);
  });

  it("offers password recovery from the login tab and hands off to the reset flow", async () => {
    stubAuth();
    const onForgotPassword = vi.fn();
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={onForgotPassword} onRegistered={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "Forgot your password?" }));

    expect(onForgotPassword).toHaveBeenCalledTimes(1);
  });

  it("hides the recovery link in register mode — there's no account to recover yet", async () => {
    stubAuth();
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} onForgotPassword={vi.fn()} onRegistered={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Forgot your password?" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Register" }));

    expect(screen.queryByRole("button", { name: "Forgot your password?" })).not.toBeInTheDocument();
  });
});
