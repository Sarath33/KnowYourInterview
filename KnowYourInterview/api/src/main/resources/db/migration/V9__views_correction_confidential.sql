-- Three additions in one migration:
--   1. view_count — a raw hit counter incremented each time a PUBLISHED experience's
--      detail page is loaded (see ExperienceService#getPublicView). Not deduped by
--      viewer/session — every load counts, including repeat views.
--   2. correction_notes — set alongside the new CORRECTION_REQUESTED status (see
--      ExperienceStatus): an admin's explanation of what to fix, shown to the
--      contributor the same way rejection_reason already is. Mirrors rejection_reason's
--      shape deliberately (nullable text, cleared on resubmit).
--   3. confidential_note — free text the submitter can attach to their own submission
--      that's visible to admins reviewing it but hidden from every other viewer
--      (purchasers, guests, other contributors). Not part of the edit-history
--      snapshot/diff mechanism (ExperienceEditSnapshot) — deliberately out of scope for
--      that feature.
ALTER TABLE experiences ADD COLUMN view_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE experiences ADD COLUMN correction_notes TEXT;
ALTER TABLE experiences ADD COLUMN confidential_note TEXT;
