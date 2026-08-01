import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ExperienceFull } from "../../../shared/types";
import { AdminReviewQueue } from "./AdminReviewQueue";
import { useAuth } from "../context/AuthContext";
import * as api from "../lib/api";

vi.mock("../context/AuthContext", () => ({
  useAuth: vi.fn(),
}));

vi.mock("../lib/api");

const mockedUseAuth = vi.mocked(useAuth);
const mockedApi = vi.mocked(api);

function pendingExperience(overrides: Partial<ExperienceFull> = {}): ExperienceFull {
  return {
    id: "exp-1",
    company: "Acme",
    roleTitle: "Backend Engineer",
    contributorId: "contributor-1",
    isRemote: false,
    outcome: "OFFER",
    teaser: "Went well overall.",
    pricePaise: 19900,
    roundCount: 1,
    viewCount: 0,
    unlockCount: 0,
    unlocked: true,
    status: "PENDING_REVIEW",
    rounds: [{ id: "round-1", roundNumber: 1, roundType: "ONSITE" }],
    proofDocuments: [{ id: "proof-1", fileName: "offer-letter.pdf", contentType: "application/pdf", uploadedAt: new Date().toISOString() }],
    ...overrides,
  };
}

describe("AdminReviewQueue", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    mockedUseAuth.mockReturnValue({
      user: {
        id: "admin-1",
        email: "admin@example.com",
        displayName: "Admin",
        isAdmin: true,
        emailVerified: true,
        createdAt: new Date().toISOString(),
      },
      accessToken: "admin-token",
      isAuthenticated: true,
      register: vi.fn(),
      login: vi.fn(),
      googleLogin: vi.fn(),
      logout: vi.fn(),
      refreshSession: vi.fn(),
    });
    mockedApi.adminReviewQueue.mockReset();
    mockedApi.adminApprove.mockReset();
    mockedApi.adminReject.mockReset();
  });

  it("renders pending experiences with their rounds and proof documents", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([pendingExperience()]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText("Acme — Backend Engineer")).toBeInTheDocument();
    expect(screen.getByText("1 round")).toBeInTheDocument();
    expect(screen.getByText("1 proof document")).toBeInTheDocument();
    expect(screen.getByText("offer-letter.pdf")).toBeInTheDocument();
  });

  // The queue used to show only "1 round(s), 1 proof document(s)" — an admin was approving
  // content they couldn't read. These assert the substance is actually on screen at the
  // moment of the decision, and that round types render as labels rather than raw enums.
  it("shows each round's content, not just a count", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([
      pendingExperience({
        rounds: [
          {
            id: "round-1",
            roundNumber: 1,
            roundType: "SYSTEM_DESIGN",
            durationMinutes: 60,
            difficulty: 4,
            topicsTags: ["sharding", "caching"],
            questionsAsked: "Design a URL shortener.",
            approach: "Started from read/write ratios.",
            interviewerBehavior: "Friendly, hinted twice.",
          },
        ],
      }),
    ]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText("Round 1 — System design")).toBeInTheDocument();
    expect(screen.getByText("Design a URL shortener.")).toBeInTheDocument();
    expect(screen.getByText("Started from read/write ratios.")).toBeInTheDocument();
    expect(screen.getByText("Friendly, hinted twice.")).toBeInTheDocument();
    expect(screen.getByText("sharding, caching")).toBeInTheDocument();
  });

  it("flags a pending submission with no proof document instead of hiding it", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([pendingExperience({ proofDocuments: [] })]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText(/should have at least one/)).toBeInTheDocument();
  });

  it("shows an empty state when nothing is pending review", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText("Nothing pending review.")).toBeInTheDocument();
  });

  it("approves an experience after confirming, then reloads the queue", async () => {
    const pending = pendingExperience();
    mockedApi.adminReviewQueue.mockResolvedValueOnce([pending]).mockResolvedValueOnce([]);
    mockedApi.adminApprove.mockResolvedValue({ ...pending, status: "PUBLISHED" });
    const user = userEvent.setup();

    render(<AdminReviewQueue />);
    await screen.findByText("Acme — Backend Engineer");

    await user.click(screen.getByRole("button", { name: /Approve & publish/ }));

    // Publishing someone else's write-up at a real price and booking a payout isn't a
    // one-click action any more — the API call only fires from the dialog.
    expect(mockedApi.adminApprove).not.toHaveBeenCalled();
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: /Approve & publish/ }));

    await waitFor(() => expect(mockedApi.adminApprove).toHaveBeenCalledWith("exp-1"));
    expect(mockedApi.adminReviewQueue).toHaveBeenCalledTimes(2);
  });

  it("cancelling the approval confirmation leaves the submission pending", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([pendingExperience()]);
    const user = userEvent.setup();

    render(<AdminReviewQueue />);
    await screen.findByText("Acme — Backend Engineer");

    await user.click(screen.getByRole("button", { name: /Approve & publish/ }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(mockedApi.adminApprove).not.toHaveBeenCalled();
  });

  it("requires a rejection reason before calling the reject endpoint", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([pendingExperience()]);
    const user = userEvent.setup();

    render(<AdminReviewQueue />);
    await screen.findByText("Acme — Backend Engineer");

    await user.click(screen.getByRole("button", { name: /Reject/ }));

    expect(await screen.findByText("Enter a rejection reason first")).toBeInTheDocument();
    expect(mockedApi.adminReject).not.toHaveBeenCalled();
  });

  it("rejects an experience with the typed reason", async () => {
    const pending = pendingExperience();
    mockedApi.adminReviewQueue.mockResolvedValueOnce([pending]).mockResolvedValueOnce([]);
    mockedApi.adminReject.mockResolvedValue({ ...pending, status: "REJECTED", rejectionReason: "Missing proof detail" });
    const user = userEvent.setup();

    render(<AdminReviewQueue />);
    await screen.findByText("Acme — Backend Engineer");

    await user.type(screen.getByPlaceholderText("Rejection reason"), "Missing proof detail");
    await user.click(screen.getByRole("button", { name: /Reject/ }));

    await waitFor(() =>
      expect(mockedApi.adminReject).toHaveBeenCalledWith("exp-1", { reason: "Missing proof detail" }),
    );
  });
});
