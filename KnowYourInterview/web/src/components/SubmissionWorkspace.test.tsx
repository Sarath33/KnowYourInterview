import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ExperienceFull, User } from "../../../shared/types";
import { SubmissionWorkspace } from "./SubmissionWorkspace";
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
    accessToken: "token-1",
    isAuthenticated: true,
    register: vi.fn(),
    login: vi.fn(),
    googleLogin: vi.fn(),
    logout: vi.fn(),
    refreshSession: vi.fn(),
    ...overrides,
  });
}

function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: "user-1",
    email: "jane@example.com",
    displayName: "Jane",
    isAdmin: false,
    emailVerified: true,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function makeExperience(overrides: Partial<ExperienceFull> = {}): ExperienceFull {
  return {
    id: "exp-1",
    company: "Acme",
    roleTitle: "Backend Engineer",
    level: "L4",
    location: "Bengaluru",
    isRemote: true,
    outcome: "OFFER",
    teaser: "Went well overall.",
    pricePaise: 9900,
    roundCount: 0,
    viewCount: 0,
    unlocked: true,
    contributorId: "user-1",
    status: "DRAFT",
    unlockCount: 0,
    rounds: [],
    proofDocuments: [],
    ...overrides,
  };
}

describe("SubmissionWorkspace", () => {
  beforeEach(() => {
    mockedUseAuth.mockReset();
    Object.values(mockedApi).forEach((fn) => {
      if (typeof fn === "function" && "mockReset" in fn) (fn as ReturnType<typeof vi.fn>).mockReset();
    });
    window.localStorage.clear();
    stubAuth({ user: makeUser() });
    mockedApi.listMyExperiences.mockResolvedValue([]);
  });

  it("loads and lists the contributor's own submissions", async () => {
    mockedApi.listMyExperiences.mockResolvedValue([
      makeExperience({ id: "exp-1", company: "Acme", roleTitle: "Backend Engineer" }),
      makeExperience({ id: "exp-2", company: "Globex", roleTitle: "SRE", status: "PUBLISHED" }),
    ]);

    render(<SubmissionWorkspace />);

    expect(await screen.findByRole("button", { name: /Acme — Backend Engineer/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Globex — SRE/ })).toBeInTheDocument();
  });

  it("shows a validation error instead of calling the API when required fields are blank", async () => {
    const user = userEvent.setup();
    render(<SubmissionWorkspace />);
    await screen.findByRole("button", { name: /New draft/ });

    await user.click(screen.getByRole("button", { name: /New draft/ }));
    await user.click(screen.getByRole("button", { name: "Create draft" }));

    expect(await screen.findByText("Company, role, and teaser are required.")).toBeInTheDocument();
    expect(mockedApi.createExperience).not.toHaveBeenCalled();
  });

  it("does not show the admin-only reference toggle to a regular contributor", async () => {
    const user = userEvent.setup();
    render(<SubmissionWorkspace />);
    await user.click(await screen.findByRole("button", { name: /New draft/ }));

    expect(screen.queryByText("Reference a public source")).not.toBeInTheDocument();
    // The free-contribution checkbox, on the other hand, is open to everyone.
    expect(screen.getByLabelText(/Contribute this for free/)).toBeInTheDocument();
  });

  it("creates a normal (paid, reviewed) draft with no source fields and freeContribution false", async () => {
    const user = userEvent.setup();
    mockedApi.createExperience.mockResolvedValue(makeExperience({ id: "new-exp" }));
    render(<SubmissionWorkspace />);
    await user.click(await screen.findByRole("button", { name: /New draft/ }));

    await user.type(screen.getByLabelText("Company"), "Acme");
    await user.type(screen.getByLabelText("Role / title"), "Backend Engineer");
    await user.type(screen.getByLabelText(/Teaser/), "Went well overall.");
    await user.click(screen.getByRole("button", { name: "Create draft" }));

    await waitFor(() =>
      expect(mockedApi.createExperience).toHaveBeenCalledWith(
        expect.objectContaining({
          company: "Acme",
          roleTitle: "Backend Engineer",
          teaser: "Went well overall.",
          sourceUrl: undefined,
          sourceName: undefined,
          freeContribution: false,
        }),
      ),
    );
  });

  it("sends freeContribution: true when the free-contribution checkbox is checked", async () => {
    const user = userEvent.setup();
    mockedApi.createExperience.mockResolvedValue(makeExperience({ id: "new-exp" }));
    render(<SubmissionWorkspace />);
    await user.click(await screen.findByRole("button", { name: /New draft/ }));

    await user.click(screen.getByLabelText(/Contribute this for free/));
    expect(screen.getByText(/Free contributions skip admin review and platform pricing/)).toBeInTheDocument();

    await user.type(screen.getByLabelText("Company"), "Acme");
    await user.type(screen.getByLabelText("Role / title"), "Backend Engineer");
    await user.type(screen.getByLabelText(/Teaser/), "Went well overall.");
    await user.click(screen.getByRole("button", { name: "Create draft" }));

    await waitFor(() =>
      expect(mockedApi.createExperience).toHaveBeenCalledWith(
        expect.objectContaining({ freeContribution: true, sourceUrl: undefined, sourceName: undefined }),
      ),
    );
  });

  it("shows the reference toggle to an admin and requires source fields when it's selected", async () => {
    const user = userEvent.setup();
    stubAuth({ user: makeUser({ isAdmin: true }) });
    render(<SubmissionWorkspace />);
    await user.click(await screen.findByRole("button", { name: /New draft/ }));

    await user.click(screen.getByRole("button", { name: "Reference a public source" }));
    // Selecting the reference toggle hides the free-contribution checkbox — the two are
    // mutually exclusive (a reference submission is free but still reviewed).
    expect(screen.queryByLabelText(/Contribute this for free/)).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("Company"), "Acme");
    await user.type(screen.getByLabelText("Role / title"), "Backend Engineer");
    await user.type(screen.getByLabelText(/Teaser/), "Went well overall.");
    await user.click(screen.getByRole("button", { name: "Create draft" }));

    expect(
      await screen.findByText("Source URL and source site/platform are required when referencing a public source."),
    ).toBeInTheDocument();
    expect(mockedApi.createExperience).not.toHaveBeenCalled();
  });

  it("creates a reference submission with the source fields and freeContribution false", async () => {
    const user = userEvent.setup();
    stubAuth({ user: makeUser({ isAdmin: true }) });
    mockedApi.createExperience.mockResolvedValue(makeExperience({ id: "new-exp" }));
    render(<SubmissionWorkspace />);
    await user.click(await screen.findByRole("button", { name: /New draft/ }));

    await user.click(screen.getByRole("button", { name: "Reference a public source" }));
    await user.type(screen.getByLabelText("Source URL"), "https://example.com/writeup");
    await user.type(screen.getByLabelText("Source site / platform"), "Blind");
    await user.type(screen.getByLabelText("Company"), "Acme");
    await user.type(screen.getByLabelText("Role / title"), "Backend Engineer");
    await user.type(screen.getByLabelText(/Teaser/), "Went well overall.");
    await user.click(screen.getByRole("button", { name: "Create draft" }));

    await waitFor(() =>
      expect(mockedApi.createExperience).toHaveBeenCalledWith(
        expect.objectContaining({
          sourceUrl: "https://example.com/writeup",
          sourceName: "Blind",
          freeContribution: false,
        }),
      ),
    );
  });

  it("shows 'Submit for review' with the review-content note for a normal draft", async () => {
    const user = userEvent.setup();
    mockedApi.listMyExperiences.mockResolvedValue([makeExperience({ status: "DRAFT" })]);
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));

    expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
    expect(screen.getByText("Needs at least one round and one proof document.")).toBeInTheDocument();
    expect(screen.getByText("Price is set by the platform on publish")).toBeInTheDocument();
  });

  it("shows 'Publish now' with the skip-review note for a self free-contribution draft", async () => {
    const user = userEvent.setup();
    mockedApi.listMyExperiences.mockResolvedValue([
      makeExperience({ status: "DRAFT", isFree: true, sourceUrl: undefined }),
    ]);
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));

    expect(screen.getByRole("button", { name: "Publish now" })).toBeInTheDocument();
    expect(screen.getByText(/Free contributions skip admin review/)).toBeInTheDocument();
    expect(screen.getByText("Free for everyone")).toBeInTheDocument();
  });

  it("still says 'Submit for review' for an admin reference draft (free, but still reviewed)", async () => {
    const user = userEvent.setup();
    mockedApi.listMyExperiences.mockResolvedValue([
      makeExperience({ status: "DRAFT", isFree: true, sourceUrl: "https://example.com/writeup", sourceName: "Blind" }),
    ]);
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));

    expect(screen.getByRole("button", { name: "Submit for review" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish now" })).not.toBeInTheDocument();
  });

  it("calls submitExperience and refreshes the list when Submit for review is clicked", async () => {
    const user = userEvent.setup();
    const draft = makeExperience({ status: "DRAFT" });
    mockedApi.listMyExperiences.mockResolvedValue([draft]);
    mockedApi.submitExperience.mockResolvedValue({ ...draft, status: "PENDING_REVIEW" });
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));
    await user.click(screen.getByRole("button", { name: "Submit for review" }));

    await waitFor(() => expect(mockedApi.submitExperience).toHaveBeenCalledWith("exp-1"));
    await waitFor(() => expect(mockedApi.listMyExperiences).toHaveBeenCalledTimes(2));
  });

  it("shows the rejection reason and a Resubmit button for a rejected submission", async () => {
    const user = userEvent.setup();
    mockedApi.listMyExperiences.mockResolvedValue([
      makeExperience({ status: "REJECTED", rejectionReason: "Needs more detail on the interview process." }),
    ]);
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));

    expect(screen.getByText(/Needs more detail on the interview process\./)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resubmit for review" })).toBeInTheDocument();
  });

  it("deletes a submission through the confirm dialog, not on the first click", async () => {
    const user = userEvent.setup();
    mockedApi.listMyExperiences.mockResolvedValue([makeExperience({ status: "DRAFT" })]);
    mockedApi.deleteExperience.mockResolvedValue(undefined);
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));
    await user.click(screen.getByRole("button", { name: "Delete submission" }));

    // The API call must not fire until the dialog is explicitly confirmed.
    expect(mockedApi.deleteExperience).not.toHaveBeenCalled();

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Delete this submission?")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Delete submission" }));

    await waitFor(() => expect(mockedApi.deleteExperience).toHaveBeenCalledWith("exp-1"));
  });

  it("adds a round to an existing draft via the round form", async () => {
    const user = userEvent.setup();
    const draft = makeExperience({ status: "DRAFT" });
    mockedApi.listMyExperiences.mockResolvedValue([draft]);
    mockedApi.addRound.mockResolvedValue({ id: "round-1", roundNumber: 1, roundType: "PHONE_SCREEN" });
    render(<SubmissionWorkspace />);

    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));
    await user.selectOptions(screen.getByLabelText("Round type"), "Phone screen");
    await user.click(screen.getByRole("button", { name: "Add round" }));

    await waitFor(() =>
      expect(mockedApi.addRound).toHaveBeenCalledWith(
        "exp-1",
        expect.objectContaining({ roundType: "PHONE_SCREEN" }),
      ),
    );
  });

  /** Disabled rather than hidden, and with an explanation: an unconfirmed contributor should
   * understand why they can't start, not just find the button missing. The server enforces
   * the same rule in ExperienceService#createDraft regardless. */
  it("blocks starting a new draft until the email is confirmed", async () => {
    stubAuth({ user: makeUser({ emailVerified: false }) });
    render(<SubmissionWorkspace />);

    expect(await screen.findByRole("button", { name: /New draft/ })).toBeDisabled();
    expect(screen.getByText(/Confirm your email address to start a submission/)).toBeInTheDocument();
  });

  it("leaves existing submissions reachable while unconfirmed", async () => {
    const user = userEvent.setup();
    stubAuth({ user: makeUser({ emailVerified: false }) });
    mockedApi.listMyExperiences.mockResolvedValue([makeExperience({ status: "DRAFT" })]);
    render(<SubmissionWorkspace />);

    // The gate is on creating something new — a draft that already exists must not become
    // unreachable, or an account could be left holding work it can't see.
    await user.click(await screen.findByRole("button", { name: /Acme — Backend Engineer/ }));
    expect(screen.getByRole("button", { name: "Add round" })).toBeInTheDocument();
  });
});
