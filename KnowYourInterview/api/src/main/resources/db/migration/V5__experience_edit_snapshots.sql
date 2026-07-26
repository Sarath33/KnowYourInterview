-- Edit history: a snapshot of an experience's top-level editable fields is saved right
-- before each real edit (ExperienceService#updateDraft), so a contributor can see what
-- changed between a rejection and a resubmission instead of the edit silently
-- overwriting the previous version. Scoped to the fields the "Edit details" form
-- actually edits — rounds have their own edit-in-place UI and aren't snapshotted here.
--
-- Cascades with the experience, unlike review_logs — this is pure content history with
-- no standalone significance once the experience itself is gone (contrast: review_logs
-- is a moderation audit trail, deliberately not cascading).

CREATE TABLE experience_edit_snapshots (
    id                  UUID PRIMARY KEY,
    experience_id       UUID NOT NULL REFERENCES experiences(id) ON DELETE CASCADE,
    company             VARCHAR(255) NOT NULL,
    role_title          VARCHAR(255) NOT NULL,
    level               VARCHAR(255),
    location            VARCHAR(255),
    is_remote           BOOLEAN NOT NULL,
    interview_month     SMALLINT,
    interview_year      SMALLINT,
    outcome             VARCHAR(50) NOT NULL,
    teaser              TEXT NOT NULL,
    prep_advice         TEXT,
    overall_difficulty  SMALLINT,
    timeline            VARCHAR(255),
    compensation        VARCHAR(255),
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_experience_edit_snapshots_experience ON experience_edit_snapshots(experience_id);
