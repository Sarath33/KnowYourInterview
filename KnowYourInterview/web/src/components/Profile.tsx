import { useState } from "react";
import type { ProfileResponse } from "../../../shared/types";
import * as api from "../lib/api";
import { useAsync } from "../lib/useAsync";
import { useAuth } from "../context/AuthContext";
import { useRouter } from "../lib/router";
import { errorMessage } from "../lib/errors";
import { formatPaise } from "../lib/format";
import { ConfirmDialog } from "./ConfirmDialog";

/** Green confirmation line under a form. There's no dedicated CSS class for this (only
 * .error-text), so it borrows the success token directly — the one inline exception. */
function SuccessText({ children }: { children: string }) {
  return <p style={{ color: "var(--success-text)", fontSize: 13, margin: 0 }}>{children}</p>;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="card card-pad-md">
      <div className="section-title">{title}</div>
      {children}
    </section>
  );
}

function signInMethod(hasPassword: boolean, hasGoogle: boolean): string {
  if (hasPassword && hasGoogle) return "Google + password";
  if (hasGoogle) return "Google";
  return "Email & password";
}

/** Read-only identity summary. */
function AccountOverview({ profile }: { profile: ProfileResponse }) {
  const { user, hasPassword, hasGoogle } = profile;
  return (
    <Section title="Account">
      <dl className="stack-sm" style={{ margin: 0 }}>
        <div className="row" style={{ gap: 8, alignItems: "center" }}>
          <span className="field-label" style={{ minWidth: 130 }}>Email</span>
          <span>{user.email}</span>
          {user.emailVerified ? (
            <span className="tag tag-sm tag-success">Verified</span>
          ) : (
            <span className="tag tag-sm tag-warning">Unverified</span>
          )}
        </div>
        <div className="row" style={{ gap: 8, alignItems: "center" }}>
          <span className="field-label" style={{ minWidth: 130 }}>Display name</span>
          <span>{user.displayName}</span>
          {user.isAdmin && <span className="tag tag-sm tag-neutral">Admin</span>}
        </div>
        <div className="row" style={{ gap: 8 }}>
          <span className="field-label" style={{ minWidth: 130 }}>Member since</span>
          <span>{new Date(user.createdAt).toLocaleDateString()}</span>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <span className="field-label" style={{ minWidth: 130 }}>Sign-in method</span>
          <span>{signInMethod(hasPassword, hasGoogle)}</span>
        </div>
      </dl>
    </Section>
  );
}

/** Read-only lifetime earnings + activity stats. */
function EarningsSummary({ profile }: { profile: ProfileResponse }) {
  return (
    <Section title="Earnings">
      <div className="row" style={{ gap: 32, flexWrap: "wrap" }}>
        <div>
          <div className="field-label">Total earned</div>
          <span className="price-tag">{formatPaise(profile.totalEarnedPaise)}</span>
        </div>
        <div>
          <div className="field-label">Pending payouts</div>
          <span className="price-tag">{formatPaise(profile.pendingPayoutPaise)}</span>
        </div>
        <div>
          <div className="field-label">Submissions</div>
          <span>{profile.submissionCount}</span>
        </div>
        <div>
          <div className="field-label">Purchases</div>
          <span>{profile.purchaseCount}</span>
        </div>
      </div>
    </Section>
  );
}

function DisplayNameForm({ profile, onSaved }: { profile: ProfileResponse; onSaved: () => Promise<void> }) {
  const { refreshSession } = useAuth();
  const [name, setName] = useState(profile.user.displayName);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.updateDisplayName({ displayName: name.trim() });
      // Refresh the session so the nav header's name updates too, then reload the profile.
      await refreshSession();
      await onSaved();
      setSuccess("Display name updated.");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title="Display name">
      <form onSubmit={submit} className="stack-sm" aria-busy={busy}>
        <div className="field">
          <label htmlFor="profile-name" className="field-label">Display name</label>
          <input
            id="profile-name"
            className="text-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
        {error && <p className="error-text">{error}</p>}
        {success && <SuccessText>{success}</SuccessText>}
        <div className="row">
          <button type="submit" className="btn btn-primary" disabled={busy || !name.trim()}>
            {busy ? "Saving…" : "Save"}
          </button>
        </div>
      </form>
    </Section>
  );
}

function ChangeEmailForm({ profile, onSaved }: { profile: ProfileResponse; onSaved: () => Promise<void> }) {
  const { refreshSession } = useAuth();
  const [newEmail, setNewEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  if (profile.hasGoogle) {
    return (
      <Section title="Email address">
        <div className="field">
          <label htmlFor="profile-email-managed" className="field-label">Email</label>
          <input id="profile-email-managed" className="text-input" value={profile.user.email} disabled />
          <p className="field-hint">Your email is managed by Google and can't be changed here.</p>
        </div>
      </Section>
    );
  }

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const res = await api.changeEmail({ newEmail: newEmail.trim() });
      await refreshSession();
      await onSaved();
      setNewEmail("");
      setSuccess(res.message);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title="Change email">
      <form onSubmit={submit} className="stack-sm" aria-busy={busy}>
        <div className="field">
          <label htmlFor="profile-new-email" className="field-label">New email</label>
          <input
            id="profile-new-email"
            type="email"
            className="text-input"
            value={newEmail}
            onChange={(e) => setNewEmail(e.target.value)}
            required
          />
          <p className="field-hint">We'll send a verification link to the new address before switching it.</p>
        </div>
        {error && <p className="error-text">{error}</p>}
        {success && <SuccessText>{success}</SuccessText>}
        <div className="row">
          <button type="submit" className="btn btn-primary" disabled={busy || !newEmail.trim()}>
            {busy ? "Sending…" : "Send verification"}
          </button>
        </div>
      </form>
    </Section>
  );
}

function ChangePasswordForm({ profile }: { profile: ProfileResponse }) {
  const settingNew = !profile.hasPassword; // Google-only account setting its first password.
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.changePassword({
        currentPassword: settingNew ? undefined : currentPassword,
        newPassword,
      });
      setCurrentPassword("");
      setNewPassword("");
      setSuccess(settingNew ? "Password set." : "Password changed.");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  const tooShort = newPassword.length < 8;

  return (
    <Section title={settingNew ? "Set a password" : "Change password"}>
      <form onSubmit={submit} className="stack-sm" aria-busy={busy}>
        {!settingNew && (
          <div className="field">
            <label htmlFor="profile-cur-pw" className="field-label">Current password</label>
            <input
              id="profile-cur-pw"
              type="password"
              className="text-input"
              value={currentPassword}
              onChange={(e) => setCurrentPassword(e.target.value)}
              required
            />
          </div>
        )}
        <div className="field">
          <label htmlFor="profile-new-pw" className="field-label">New password</label>
          <input
            id="profile-new-pw"
            type="password"
            className="text-input"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            minLength={8}
            required
          />
          <p className="field-hint">At least 8 characters.</p>
        </div>
        {error && <p className="error-text">{error}</p>}
        {success && <SuccessText>{success}</SuccessText>}
        <div className="row">
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || tooShort || (!settingNew && !currentPassword)}
          >
            {busy ? "Saving…" : settingNew ? "Set password" : "Change password"}
          </button>
        </div>
      </form>
    </Section>
  );
}

// Mirrors the server-side @Pattern on UpsertPayoutAccountRequest: handle@psp.
const VPA_PATTERN = /^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z][a-zA-Z0-9]{1,63}$/;

function PayoutForm({ profile, onSaved }: { profile: ProfileResponse; onSaved: () => Promise<void> }) {
  const [accountHolderName, setAccountHolderName] = useState(profile.payoutAccount?.accountHolderName ?? "");
  const [upiVpa, setUpiVpa] = useState(profile.payoutAccount?.upiVpa ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    // VPAs are case-insensitive; normalize to match how the server stores them. Mirrors the
    // server-side @Pattern so an obvious typo is caught before a round-trip.
    const normalizedVpa = upiVpa.trim().toLowerCase();
    if (!VPA_PATTERN.test(normalizedVpa)) {
      setSuccess(null);
      setError("Enter a valid UPI ID, e.g. name@bank.");
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await api.savePayoutAccount({ accountHolderName: accountHolderName.trim(), upiVpa: normalizedVpa });
      await onSaved();
      setSuccess("Payout details saved.");
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title="Payout details">
      <form onSubmit={submit} className="stack-sm" aria-busy={busy}>
        <div className="field">
          <label htmlFor="profile-payee" className="field-label">Account holder name</label>
          <input
            id="profile-payee"
            className="text-input"
            value={accountHolderName}
            onChange={(e) => setAccountHolderName(e.target.value)}
            required
          />
        </div>
        <div className="field">
          <label htmlFor="profile-vpa" className="field-label">UPI ID (VPA)</label>
          <input
            id="profile-vpa"
            className="text-input"
            value={upiVpa}
            onChange={(e) => setUpiVpa(e.target.value)}
            placeholder="name@bank"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            inputMode="email"
            required
          />
          <p className="field-hint">Used for manual payouts when one of your experiences earns a flat fee.</p>
        </div>
        {error && <p className="error-text">{error}</p>}
        {success && <SuccessText>{success}</SuccessText>}
        <div className="row">
          <button
            type="submit"
            className="btn btn-primary"
            disabled={busy || !accountHolderName.trim() || !upiVpa.trim()}
          >
            {busy ? "Saving…" : "Save payout details"}
          </button>
        </div>
      </form>
    </Section>
  );
}

function DangerZone({ profile }: { profile: ProfileResponse }) {
  const { logout } = useAuth();
  const { navigate } = useRouter();
  const requiresPassword = profile.hasPassword;
  const [confirmValue, setConfirmValue] = useState("");
  const [dialogOpen, setDialogOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A password account confirms by re-entering its password; a Google-only account has none,
  // so it confirms by typing the word DELETE instead.
  const canDelete = requiresPassword ? confirmValue.length > 0 : confirmValue === "DELETE";

  const doDelete = async () => {
    setBusy(true);
    setError(null);
    try {
      await api.deleteAccount({ password: requiresPassword ? confirmValue : undefined });
      await logout();
      navigate("/");
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
      setDialogOpen(false);
    }
  };

  return (
    <Section title="Delete account">
      <p style={{ fontSize: 14, color: "var(--text-secondary)", lineHeight: 1.5, marginTop: 0 }}>
        This is permanent and can't be undone. Any experiences you've already published stay
        available to buyers who paid for them — your account is anonymized rather than removing
        their purchase.
      </p>
      <div className="field">
        <label htmlFor="profile-delete-confirm" className="field-label">
          {requiresPassword ? "Confirm your password" : "Type DELETE to confirm"}
        </label>
        <input
          id="profile-delete-confirm"
          type={requiresPassword ? "password" : "text"}
          className="text-input"
          value={confirmValue}
          onChange={(e) => setConfirmValue(e.target.value)}
        />
      </div>
      {error && <p className="error-text" style={{ marginTop: 8 }}>{error}</p>}
      <div className="row" style={{ marginTop: 12 }}>
        <button
          type="button"
          className="btn btn-outline btn-outline-danger"
          disabled={!canDelete || busy}
          onClick={() => setDialogOpen(true)}
        >
          Delete account
        </button>
      </div>
      {dialogOpen && (
        <ConfirmDialog
          title="Delete your account?"
          message="This permanently deletes your account and can't be undone. Published experiences stay available to buyers who already paid for them."
          confirmLabel="Delete account"
          busyLabel="Deleting…"
          confirming={busy}
          tone="danger"
          onConfirm={doDelete}
          onCancel={() => setDialogOpen(false)}
        />
      )}
    </Section>
  );
}

export function Profile() {
  const { data, loading, error, refetch } = useAsync(() => api.getProfile(), []);

  return (
    <div>
      <h1 className="page-title" style={{ marginBottom: 6 }}>Account</h1>
      <p className="page-subtext" style={{ marginBottom: 24 }}>
        Manage your profile, sign-in, payout details, and account.
      </p>
      {error && <p className="error-text" style={{ marginBottom: 16 }}>{error}</p>}
      {loading ? (
        <p className="muted" aria-busy="true" aria-live="polite">Loading…</p>
      ) : data ? (
        <div className="stack-md" style={{ gap: 20 }}>
          <AccountOverview profile={data} />
          <EarningsSummary profile={data} />
          <DisplayNameForm key={`name-${data.user.displayName}`} profile={data} onSaved={refetch} />
          <ChangeEmailForm profile={data} onSaved={refetch} />
          <ChangePasswordForm profile={data} />
          <PayoutForm
            key={`payout-${data.payoutAccount?.upiVpa ?? ""}-${data.payoutAccount?.accountHolderName ?? ""}`}
            profile={data}
            onSaved={refetch}
          />
          <DangerZone profile={data} />
        </div>
      ) : null}
    </div>
  );
}
