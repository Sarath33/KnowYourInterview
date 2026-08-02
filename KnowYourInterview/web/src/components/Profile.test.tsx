import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ProfileResponse, User } from "../../../shared/types";
import { Profile } from "./Profile";
import { useAuth } from "../context/AuthContext";
import { useRouter } from "../lib/router";
import * as api from "../lib/api";

vi.mock("../context/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../lib/router", () => ({
  useRouter: vi.fn(),
}));

vi.mock("../lib/api");

const mockedUseAuth = vi.mocked(useAuth);
const mockedUseRouter = vi.mocked(useRouter);
const mockedApi = vi.mocked(api);

function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: "user-1",
    email: "jane@example.com",
    displayName: "Jane",
    isAdmin: false,
    emailVerified: true,
    createdAt: "2026-01-15T00:00:00.000Z",
    ...overrides,
  };
}

function makeProfile(overrides: Partial<ProfileResponse> = {}): ProfileResponse {
  return {
    user: makeUser(overrides.user),
    hasPassword: true,
    hasGoogle: false,
    payoutAccount: null,
    totalEarnedPaise: 250000,
    pendingPayoutPaise: 50000,
    submissionCount: 3,
    purchaseCount: 7,
    ...overrides,
  };
}

function stubAuth(overrides: Partial<ReturnType<typeof useAuth>> = {}) {
  mockedUseAuth.mockReturnValue({
    user: makeUser(),
    accessToken: "token",
    isAuthenticated: true,
    register: vi.fn(),
    login: vi.fn(),
    googleLogin: vi.fn(),
    logout: vi.fn(),
    refreshSession: vi.fn(),
    ...overrides,
  });
}

const navigate = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
  mockedUseRouter.mockReturnValue({
    pathname: "/profile",
    search: "",
    canGoBack: false,
    navigate,
    goBack: vi.fn(),
  });
  stubAuth();
});

describe("Profile", () => {
  it("renders the loaded account and earnings data", async () => {
    mockedApi.getProfile.mockResolvedValue(makeProfile());

    render(<Profile />);

    expect(await screen.findByText("jane@example.com")).toBeInTheDocument();
    expect(screen.getByText("Verified")).toBeInTheDocument();
    // Display name appears both in the overview and prefilled into the edit input.
    expect(screen.getAllByDisplayValue("Jane").length).toBeGreaterThan(0);
    // 250000 paise -> ₹2500.00
    expect(screen.getByText("₹2500.00")).toBeInTheDocument();
  });

  it("edits the display name and refreshes the session", async () => {
    const refreshSession = vi.fn().mockResolvedValue(undefined);
    stubAuth({ refreshSession });
    mockedApi.getProfile.mockResolvedValue(makeProfile());
    mockedApi.updateDisplayName.mockResolvedValue(makeUser({ displayName: "Janet" }));
    const user = userEvent.setup();

    render(<Profile />);
    const input = await screen.findByLabelText("Display name");
    await user.clear(input);
    await user.type(input, "Janet");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(mockedApi.updateDisplayName).toHaveBeenCalledWith({ displayName: "Janet" }));
    expect(refreshSession).toHaveBeenCalledTimes(1);
  });

  it("hides the email change form for a Google-managed account", async () => {
    mockedApi.getProfile.mockResolvedValue(makeProfile({ hasPassword: false, hasGoogle: true }));

    render(<Profile />);

    await screen.findByText("jane@example.com");
    expect(screen.queryByLabelText("New email")).not.toBeInTheDocument();
    expect(screen.getByText(/managed by Google/)).toBeInTheDocument();
  });

  it("saves the payout account", async () => {
    mockedApi.getProfile.mockResolvedValue(makeProfile());
    mockedApi.savePayoutAccount.mockResolvedValue({ accountHolderName: "Jane Doe", upiVpa: "jane@upi" });
    const user = userEvent.setup();

    render(<Profile />);
    await user.type(await screen.findByLabelText("Account holder name"), "Jane Doe");
    await user.type(screen.getByLabelText("UPI ID (VPA)"), "jane@upi");
    await user.click(screen.getByRole("button", { name: "Save payout details" }));

    await waitFor(() =>
      expect(mockedApi.savePayoutAccount).toHaveBeenCalledWith({ accountHolderName: "Jane Doe", upiVpa: "jane@upi" }),
    );
  });

  it("deletes a password account after confirming with the password", async () => {
    const logout = vi.fn().mockResolvedValue(undefined);
    stubAuth({ logout });
    mockedApi.getProfile.mockResolvedValue(makeProfile());
    mockedApi.deleteAccount.mockResolvedValue(undefined);
    const user = userEvent.setup();

    render(<Profile />);
    await user.type(await screen.findByLabelText("Confirm your password"), "hunter2");
    // The danger-zone button opens the confirm dialog, which owns the final destructive click.
    await user.click(screen.getByRole("button", { name: "Delete account" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Delete account" }));

    await waitFor(() => expect(mockedApi.deleteAccount).toHaveBeenCalledWith({ password: "hunter2" }));
    expect(logout).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(navigate).toHaveBeenCalledWith("/"));
  });

  it("requires typing DELETE for a Google-only account before deletion is possible", async () => {
    stubAuth();
    mockedApi.getProfile.mockResolvedValue(makeProfile({ hasPassword: false, hasGoogle: true }));
    const user = userEvent.setup();

    render(<Profile />);
    const confirmField = await screen.findByLabelText("Type DELETE to confirm");
    const deleteButton = screen.getByRole("button", { name: "Delete account" });
    expect(deleteButton).toBeDisabled();

    await user.type(confirmField, "DELETE");
    expect(deleteButton).toBeEnabled();
  });
});
