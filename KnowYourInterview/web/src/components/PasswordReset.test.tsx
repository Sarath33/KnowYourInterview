import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { RequestPasswordReset, ResetPassword } from "./PasswordReset";
import * as api from "../lib/api";

vi.mock("../lib/api");

const mockedApi = vi.mocked(api);

describe("RequestPasswordReset", () => {
  beforeEach(() => {
    mockedApi.forgotPassword.mockReset();
  });

  it("submits the email and confirms without claiming the account exists", async () => {
    mockedApi.forgotPassword.mockResolvedValue({ message: "If an account exists…" });
    const user = userEvent.setup();

    render(<RequestPasswordReset onBackToLogin={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));

    await waitFor(() => expect(mockedApi.forgotPassword).toHaveBeenCalledWith({ email: "jane@example.com" }));
    // "If an account exists" — never "we've sent you an email", which would leak whether
    // the address is registered and would also be untrue while delivery is a log line.
    expect(await screen.findByText(/If an account exists/)).toBeInTheDocument();
  });

  it("surfaces a failure instead of falsely confirming", async () => {
    mockedApi.forgotPassword.mockRejectedValue(new Error("Too many attempts from this address"));
    const user = userEvent.setup();

    render(<RequestPasswordReset onBackToLogin={vi.fn()} />);

    await user.type(screen.getByLabelText("Email"), "jane@example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));

    expect(await screen.findByText("Too many attempts from this address")).toBeInTheDocument();
    expect(screen.queryByText(/If an account exists/)).not.toBeInTheDocument();
  });
});

describe("ResetPassword", () => {
  beforeEach(() => {
    mockedApi.resetPassword.mockReset();
  });

  it("sends the token with the new password and confirms success", async () => {
    mockedApi.resetPassword.mockResolvedValue({ message: "Password updated." });
    const user = userEvent.setup();

    render(<ResetPassword token="raw-token" onDone={vi.fn()} />);

    await user.type(screen.getByLabelText("New password"), "hunter2222");
    await user.type(screen.getByLabelText("Confirm new password"), "hunter2222");
    await user.click(screen.getByRole("button", { name: "Set new password" }));

    await waitFor(() =>
      expect(mockedApi.resetPassword).toHaveBeenCalledWith({ token: "raw-token", newPassword: "hunter2222" }),
    );
    expect(await screen.findByText("Password updated")).toBeInTheDocument();
  });

  it("catches a mismatched confirmation before spending the single-use token", async () => {
    const user = userEvent.setup();

    render(<ResetPassword token="raw-token" onDone={vi.fn()} />);

    await user.type(screen.getByLabelText("New password"), "hunter2222");
    await user.type(screen.getByLabelText("Confirm new password"), "hunter2223");
    await user.click(screen.getByRole("button", { name: "Set new password" }));

    expect(await screen.findByText("Those passwords don't match.")).toBeInTheDocument();
    expect(mockedApi.resetPassword).not.toHaveBeenCalled();
  });

  it("explains a link with no token rather than showing a form that can only fail", () => {
    render(<ResetPassword token={null} onDone={vi.fn()} />);

    expect(screen.getByText("That link looks incomplete")).toBeInTheDocument();
    expect(screen.queryByLabelText("New password")).not.toBeInTheDocument();
  });

  it("reports an expired or already-used token", async () => {
    mockedApi.resetPassword.mockRejectedValue(new Error("Invalid or expired reset token"));
    const user = userEvent.setup();

    render(<ResetPassword token="stale-token" onDone={vi.fn()} />);

    await user.type(screen.getByLabelText("New password"), "hunter2222");
    await user.type(screen.getByLabelText("Confirm new password"), "hunter2222");
    await user.click(screen.getByRole("button", { name: "Set new password" }));

    expect(await screen.findByText("Invalid or expired reset token")).toBeInTheDocument();
  });
});
