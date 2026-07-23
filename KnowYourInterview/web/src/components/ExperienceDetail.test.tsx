import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ExperienceFull, ExperienceTeaser, ExperienceView, User } from "../../../shared/types";
import { ExperienceDetail } from "./ExperienceDetail";
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
    logout: vi.fn(),
    ...overrides,
  });
}

const teaser: ExperienceTeaser = {
  id: "exp-1",
  company: "Acme",
  roleTitle: "Backend Engineer",
  level: "L4",
  location: "Bengaluru",
  isRemote: true,
  outcome: "OFFER",
  teaser: "Went well overall, three rounds.",
  pricePaise: 19900,
  roundCount: 1,
  unlocked: false,
};

const fullExperience: ExperienceFull = {
  ...teaser,
  unlocked: true,
  contributorId: "contributor-1",
  status: "PUBLISHED",
  prepAdvice: "Practice system design.",
  timeline: "3 weeks",
  compensation: "35 LPA",
  overallDifficulty: 3,
  unlockCount: 2,
  rounds: [
    {
      id: "round-1",
      roundNumber: 1,
      roundType: "ONSITE",
      questionsAsked: "Reverse a linked list",
      difficulty: 3,
    },
  ],
  proofDocuments: [],
};

describe("ExperienceDetail", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedApi.getExperience.mockReset();
    mockedApi.createPurchaseOrder.mockReset();
    mockedApi.confirmPurchase.mockReset();
    delete (window as { Razorpay?: unknown }).Razorpay;
  });

  afterEach(() => {
    delete (window as { Razorpay?: unknown }).Razorpay;
  });

  it("shows the teaser with an Unlock button for an authenticated viewer without entitlement", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "token-1" });
    const view: ExperienceView = { entitled: false, teaser };
    mockedApi.getExperience.mockResolvedValue(view);

    render(<ExperienceDetail experienceId="exp-1" onClose={vi.fn()} onLoginRequired={vi.fn()} />);

    expect(await screen.findByText("Acme — Backend Engineer")).toBeInTheDocument();
    expect(screen.getByText("Went well overall, three rounds.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Unlock ₹199\.00/ })).toBeInTheDocument();
  });

  it("shows a log-in prompt instead of an Unlock button for an unauthenticated viewer", async () => {
    stubAuth({ isAuthenticated: false, accessToken: null });
    const view: ExperienceView = { entitled: false, teaser };
    mockedApi.getExperience.mockResolvedValue(view);
    const onLoginRequired = vi.fn();

    render(<ExperienceDetail experienceId="exp-1" onClose={vi.fn()} onLoginRequired={onLoginRequired} />);

    await screen.findByText("Acme — Backend Engineer");
    expect(screen.queryByRole("button", { name: /Unlock/ })).not.toBeInTheDocument();
    expect(screen.getByText(/to unlock this experience/)).toBeInTheDocument();

    // The "Log in" link should actually navigate to the auth screen, not just sit
    // there as a dead href="#" (the bug this test now guards against).
    const clickUser = userEvent.setup();
    await clickUser.click(screen.getByRole("link", { name: "Log in" }));
    expect(onLoginRequired).toHaveBeenCalledTimes(1);
  });

  it("renders full round detail once the viewer is entitled", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "token-1" });
    const view: ExperienceView = { entitled: true, full: fullExperience };
    mockedApi.getExperience.mockResolvedValue(view);

    render(<ExperienceDetail experienceId="exp-1" onClose={vi.fn()} onLoginRequired={vi.fn()} />);

    expect(await screen.findByText("Round 1 — ONSITE")).toBeInTheDocument();
    expect(screen.getByText(/Reverse a linked list/)).toBeInTheDocument();
    expect(screen.getByText("Practice system design.")).toBeInTheDocument();
  });

  it("creates a purchase order and opens Razorpay checkout when Unlock is clicked", async () => {
    const user: User = {
      id: "user-1",
      email: "jane@example.com",
      displayName: "Jane",
      isAdmin: false,
      createdAt: new Date().toISOString(),
    };
    stubAuth({ isAuthenticated: true, accessToken: "token-1", user });
    mockedApi.getExperience.mockResolvedValue({ entitled: false, teaser });
    mockedApi.createPurchaseOrder.mockResolvedValue({
      experienceId: "exp-1",
      razorpayOrderId: "order_123",
      amountPaise: 19900,
      currency: "INR",
      razorpayKeyId: "rzp_test_key",
    });

    const open = vi.fn();
    const on = vi.fn();
    const razorpayCtor = vi.fn().mockImplementation(() => ({ open, on }));
    (window as unknown as { Razorpay: unknown }).Razorpay = razorpayCtor;

    const clickUser = userEvent.setup();
    render(<ExperienceDetail experienceId="exp-1" onClose={vi.fn()} onLoginRequired={vi.fn()} />);

    const unlockButton = await screen.findByRole("button", { name: /Unlock ₹199\.00/ });
    await clickUser.click(unlockButton);

    await waitFor(() => expect(mockedApi.createPurchaseOrder).toHaveBeenCalledWith("token-1", "exp-1"));
    expect(razorpayCtor).toHaveBeenCalledWith(
      expect.objectContaining({
        key: "rzp_test_key",
        amount: 19900,
        currency: "INR",
        order_id: "order_123",
      }),
    );
    expect(open).toHaveBeenCalledTimes(1);
  });
});
