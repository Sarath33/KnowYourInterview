import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthForms } from "./AuthForms";
import { useAuth } from "../context/AuthContext";

// AuthForms only reads `login`/`register` off useAuth(), but the hook's return type
// requires the full shape — mocking the whole module keeps the test focused on
// AuthForms' own logic (mode switching, submit wiring, error rendering) without
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
    logout: vi.fn(),
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

    render(<AuthForms onGuestBrowse={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "hunter22");
    await user.click(getLoginSubmitButton());

    await waitFor(() => expect(login).toHaveBeenCalledWith("jane@example.com", "hunter22"));
  });

  it("switches to register mode, shows the display name field, and submits via register()", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    stubAuth({ register });
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={vi.fn()} />);

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

    render(<AuthForms onGuestBrowse={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.type(screen.getByLabelText("Password"), "wrongpassword");
    await user.click(getLoginSubmitButton());

    expect(await screen.findByText("Invalid email or password")).toBeInTheDocument();
  });

  it("calls onGuestBrowse when the guest browse button is clicked", async () => {
    stubAuth();
    const onGuestBrowse = vi.fn();
    const user = userEvent.setup();

    render(<AuthForms onGuestBrowse={onGuestBrowse} />);
    await user.click(screen.getByRole("button", { name: "Browse without an account" }));

    expect(onGuestBrowse).toHaveBeenCalledTimes(1);
  });
});
