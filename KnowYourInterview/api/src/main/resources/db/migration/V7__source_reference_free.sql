-- Admin-only "reference a public source" submissions: summarize/link to an already-public
-- interview writeup instead of the contributor's own account. These are always free (no
-- paywall) — see ExperienceService#createDraft, which forces is_free = true and
-- price_paise = 0 whenever source_url is set, and rejects the attempt entirely unless the
-- creator is an admin.
ALTER TABLE experiences ADD COLUMN source_url VARCHAR(2048);
ALTER TABLE experiences ADD COLUMN source_name VARCHAR(255);
ALTER TABLE experiences ADD COLUMN is_free BOOLEAN NOT NULL DEFAULT FALSE;
