-- Scaling prep for ExperienceRepository#browsePublished, which today full-scans the
-- (already status/published_at-indexed, see V4) PUBLISHED rows for its remaining filters:
--   - company/roleTitle/level: case-insensitive EQUALITY (LOWER(x) = LOWER(:param))
--   - search: case-insensitive SUBSTRING match on company/roleTitle/teaser
--     (LOWER(x) LIKE '%term%') — a leading wildcard means a plain btree index can't help
--     at all, regardless of what it's built on.
-- Harmless at today's data volume — this is a pre-emptive index pass, not a fix for an
-- observed slowdown.

-- pg_trgm gives Postgres a trigram similarity index, which — unlike a plain btree — can
-- serve a '%term%' LIKE/ILIKE query. Standard, widely-available Postgres contrib
-- extension (present on Railway's managed Postgres and every mainstream host); requires
-- the connecting role to be allowed to create extensions, which the app's own database
-- role already is on a self-owned database.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Equality filters: cheap, standard functional btree indexes on the lower-cased column —
-- matches the LOWER(e.company) = LOWER(:company) shape of the query exactly.
CREATE INDEX idx_experiences_lower_company ON experiences (LOWER(company));
CREATE INDEX idx_experiences_lower_role_title ON experiences (LOWER(role_title));
CREATE INDEX idx_experiences_lower_level ON experiences (LOWER(level));

-- Substring search: GIN trigram indexes on the same three columns the `search` param
-- checks. Each is independently useful since the query ORs across all three rather than
-- concatenating them into one searchable column.
CREATE INDEX idx_experiences_company_trgm ON experiences USING gin (LOWER(company) gin_trgm_ops);
CREATE INDEX idx_experiences_role_title_trgm ON experiences USING gin (LOWER(role_title) gin_trgm_ops);
CREATE INDEX idx_experiences_teaser_trgm ON experiences USING gin (LOWER(teaser) gin_trgm_ops);
