-- Phase 2 — password reset tokens (email delivery is stubbed until an email
-- provider is wired up; the token is logged server-side instead for now).

CREATE TABLE password_reset_tokens (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(255) NOT NULL UNIQUE,  -- SHA-256 hex of the raw token; raw token is never stored
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens(user_id);
