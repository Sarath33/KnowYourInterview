import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
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
      user: { id: "admin-1", email: "admin@example.com", displayName: "Admin", isAdmin: true, createdAt: new Date().toISOString() },
      accessToken: "admin-token",
      isAuthenticated: true,
      register: vi.fn(),
      login: vi.fn(),
      logout: vi.fn(),
    });
    mockedApi.adminReviewQueue.mockReset();
    mockedApi.adminApprove.mockReset();
    mockedApi.adminReject.mockReset();
  });

  it("renders pending experiences with their round and proof counts", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([pendingExperience()]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText("Acme — Backend Engineer")).toBeInTheDocument();
    expect(screen.getByText("1 round(s), 1 proof document(s)")).toBeInTheDocument();
    expect(screen.getByText("offer-letter.pdf")).toBeInTheDocument();
  });

  it("shows an empty state when nothing is pending review", async () => {
    mockedApi.adminReviewQueue.mockResolvedValue([]);

    render(<AdminReviewQueue />);

    expect(await screen.findByText("Nothing pending review.")).toBeInTheDocument();
  });

  it("approves an experience and reloads the queue", async () => {
    const pending = pendingExperience();
    mockedApi.adminReviewQueue.mockResolvedValueOnce([pending]).mockResolvedValueOnce([]);
    mockedApi.adminApprove.mockResolvedValue({ ...pending, status: "PUBLISHED" });
    const user = userEvent.setup();

    render(<AdminReviewQueue />);
    await screen.findByText("Acme — Backend Engineer");

    await user.click(screen.getByRole("button", { name: /Approve & publish/ }));

    await waitFor(() => expect(mockedApi.adminApprove).toHaveBeenCalledWith("admin-token", "exp-1"));
    expect(mockedApi.adminReviewQueue).toHaveBeenCalledTimes(2);
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
      expect(mockedApi.adminReject).toHaveBeenCalledWith("admin-token", "exp-1", { reason: "Missing proof detail" }),
    );
  });
});
