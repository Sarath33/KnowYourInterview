-- Email confirmation for email/password registrations.
--
-- Two things here: a flag on users, and a token table shaped exactly like
-- password_reset_tokens (V2) — same single-use, hashed-token, expiring design, for the same
-- reason (the raw token travels in a link and is never stored).

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Grandfather every account that already exists. Without this, deploying the gate would
-- immediately lock every current user — including the operator's own admin account — out of
-- submitting and purchasing until they each clicked a confirmation link, which is a
-- self-inflicted outage rather than a security improvement. Only registrations from here on
-- start unverified. Note this runs against the state of the table at migration time; the
-- column default (FALSE) is what applies to every row inserted afterwards.
UPDATE users SET email_verified = TRUE;

-- Google-authenticated accounts are verified by definition: Google won't issue an ID token
-- with email_verified=true for an address the account holder doesn't control, and
-- AuthService only trusts the address when that claim is set. Belt and braces alongside the
-- backfill above, and it's what keeps future Google signups from ever needing a
-- confirmation email (see User#forGoogleSignup).
UPDATE users SET email_verified = TRUE WHERE google_sub IS NOT NULL;

CREATE TABLE email_verification_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,  -- SHA-256 hex of the raw token; raw token is never stored
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_verification_tokens_user ON email_verification_tokens(user_id);
