import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ExperienceComment, User } from "../../../shared/types";
import { CommentsSection } from "./CommentsSection";
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
    emailVerified: true,
    createdAt: "2026-01-15T00:00:00.000Z",
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

function makeComment(overrides: Partial<ExperienceComment> = {}): ExperienceComment {
  return {
    id: "c-1",
    parentId: null,
    authorId: "author-x",
    authorName: "Priya",
    body: "Great write-up, thanks!",
    createdAt: new Date().toISOString(),
    deleted: false,
    canDelete: false,
    authorIsContributor: false,
    replies: [],
    ...overrides,
  };
}

const tree: ExperienceComment[] = [
  makeComment({
    id: "top-1",
    authorName: "Priya",
    body: "Great write-up, thanks!",
    authorIsContributor: true,
    replies: [
      makeComment({
        id: "reply-1",
        parentId: "top-1",
        authorName: "Ravi",
        body: "Agreed, super helpful.",
      }),
    ],
  }),
  makeComment({
    id: "top-2",
    authorName: null,
    body: "",
    deleted: true,
    replies: [],
  }),
];

beforeEach(() => {
  vi.clearAllMocks();
  stubAuth();
});

describe("CommentsSection", () => {
  const render_ = () =>
    render(
      <CommentsSection experienceId="exp-1" authorId="author-x" onLoginRequired={vi.fn()} />,
    );

  it("renders the comment tree with a reply, an Author badge, and a [deleted] tombstone", async () => {
    mockedApi.listComments.mockResolvedValue(tree);

    render_();

    expect(await screen.findByText("Great write-up, thanks!")).toBeInTheDocument();
    // Nested reply is shown.
    expect(screen.getByText("Agreed, super helpful.")).toBeInTheDocument();
    // Author badge for the experience's own contributor.
    expect(screen.getByText("Author")).toBeInTheDocument();
    // Soft-deleted comment renders as a tombstone.
    expect(screen.getAllByText("[deleted]").length).toBeGreaterThan(0);
    // Header counts all nodes (2 top-level + 1 reply).
    expect(screen.getByText("Discussion (3)")).toBeInTheDocument();
  });

  it("shows an empty state when there are no comments", async () => {
    mockedApi.listComments.mockResolvedValue([]);

    render_();

    expect(await screen.findByText(/No comments yet/)).toBeInTheDocument();
    expect(screen.getByText("Discussion (0)")).toBeInTheDocument();
  });

  it("treats an undefined result (auto-mocked api) as an empty thread without throwing", async () => {
    mockedApi.listComments.mockResolvedValue(undefined as unknown as ExperienceComment[]);

    render_();

    expect(await screen.findByText(/No comments yet/)).toBeInTheDocument();
  });

  it("lets a verified signed-in user post a top-level comment, then refetches", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "t", user: makeUser() });
    mockedApi.listComments.mockResolvedValue([]);
    mockedApi.createComment.mockResolvedValue(makeComment());
    const user = userEvent.setup();

    render_();

    const box = await screen.findByLabelText("Add a comment");
    await user.type(box, "  My first comment  ");
    await user.click(screen.getByRole("button", { name: "Post" }));

    await waitFor(() =>
      expect(mockedApi.createComment).toHaveBeenCalledWith("exp-1", {
        body: "My first comment",
        parentId: null,
      }),
    );
    // Once for the initial load, once after posting.
    expect(mockedApi.listComments.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("shows a Log in prompt and no composer for an unauthenticated viewer", async () => {
    stubAuth({ isAuthenticated: false });
    mockedApi.listComments.mockResolvedValue([]);
    const onLoginRequired = vi.fn();
    const user = userEvent.setup();

    render(
      <CommentsSection experienceId="exp-1" authorId="author-x" onLoginRequired={onLoginRequired} />,
    );

    await screen.findByText(/join the discussion/);
    expect(screen.queryByLabelText("Add a comment")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Log in" }));
    expect(onLoginRequired).toHaveBeenCalledTimes(1);
  });

  it("shows the confirm-email note and no composer for an unverified user", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "t", user: makeUser({ emailVerified: false }) });
    mockedApi.listComments.mockResolvedValue([]);

    render_();

    expect(await screen.findByText(/Confirm your email to comment/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Add a comment")).not.toBeInTheDocument();
  });

  it("posts a reply with the parent id and closes the reply composer", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "t", user: makeUser() });
    mockedApi.listComments.mockResolvedValue(tree);
    mockedApi.createComment.mockResolvedValue(makeComment());
    const user = userEvent.setup();

    render_();

    await screen.findByText("Great write-up, thanks!");
    // The first top-level comment's Reply button opens an inline composer.
    await user.click(screen.getAllByRole("button", { name: "Reply" })[0]);
    const replyBox = await screen.findByLabelText("Write a reply");
    await user.type(replyBox, "Thanks for sharing!");
    // The top-level toggle now reads "Cancel", so the only "Reply" button is the composer's submit.
    await user.click(screen.getByRole("button", { name: "Reply" }));

    await waitFor(() =>
      expect(mockedApi.createComment).toHaveBeenCalledWith("exp-1", {
        body: "Thanks for sharing!",
        parentId: "top-1",
      }),
    );
  });

  it("deletes a comment after confirming", async () => {
    stubAuth({ isAuthenticated: true, accessToken: "t", user: makeUser() });
    mockedApi.listComments.mockResolvedValue([
      makeComment({ id: "mine-1", authorName: "Jane", body: "Delete me", canDelete: true }),
    ]);
    mockedApi.deleteComment.mockResolvedValue(undefined);
    const user = userEvent.setup();

    render_();

    await screen.findByText("Delete me");
    await user.click(screen.getByRole("button", { name: "Delete" }));

    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));

    await waitFor(() => expect(mockedApi.deleteComment).toHaveBeenCalledWith("exp-1", "mine-1"));
  });
});
