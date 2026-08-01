-- One row per (experience, signed-in viewer) pair that's ever loaded the experience's
-- published detail page — the UNIQUE constraint is what makes a view "one per user"
-- instead of counting every page load (the previous behavior of the plain view_count
-- counter added in V9). Guests are never recorded here — no reliable identity to dedupe
-- against without adding session/cookie tracking — so guest traffic doesn't move the count.
CREATE TABLE experience_views (
    id              UUID PRIMARY KEY,
    experience_id   UUID NOT NULL REFERENCES experiences(id),
    viewer_id       UUID NOT NULL REFERENCES users(id),
    viewed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experience_id, viewer_id)
);

CREATE INDEX idx_experience_views_experience_id ON experience_views(experience_id);
