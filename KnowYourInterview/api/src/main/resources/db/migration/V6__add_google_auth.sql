-- Google Sign-In support (added alongside existing email/password auth, not replacing it).
-- Accounts created via Google never get a password_hash, so it has to stop being NOT NULL.
-- google_sub is Google's stable per-user subject id (from the verified ID token's "sub"
-- claim) — unique per Google account, used to find a returning Google-auth user on login.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255) UNIQUE;
