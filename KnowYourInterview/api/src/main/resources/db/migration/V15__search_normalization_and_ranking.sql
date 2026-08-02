-- Browse search, Tier 1 + Tier 2 (see ExperienceRepository#browsePublished).
--
-- Problem this fixes: the old browse query matched company/role/level with case-insensitive
-- EQUALITY (LOWER(x) = LOWER(:param)) and searched with a punctuation-sensitive substring
-- LIKE. So "SDE3" never matched a stored "SDE-3", and typing "SDE" returned nothing unless
-- it equalled the whole stored value. There was also no relevance ranking on search.
--
-- Tier 1 — normalized "contains" filters. Three STORED generated columns hold a
-- punctuation-stripped, lower-cased form of company/role_title/level, so "SDE-3", "SDE 3"
-- and "sde3" all collapse to "sde3" and a normalized substring match ("%sde3%", "%sde%")
-- can drive the filters. STORED (not VIRTUAL) so a GIN trigram index can be built on them.
-- level is nullable, so coalesce('') keeps its normalized form as '' rather than NULL —
-- an empty level never matches a non-empty filter pattern, which is the intent.
--
-- Tier 2 — typo-tolerant, relevance-ranked search. The similarity() ranking runs against
-- the existing V8 trigram indexes on lower(company)/lower(role_title)/lower(teaser); the
-- new normalized-column indexes below serve the normalized '%...%' contains checks.
--
-- pg_trgm is already installed (V8). These generated columns are deliberately NOT mapped on
-- the Experience JPA entity — ddl-auto: validate ignores extra DB columns, and the native
-- browse query reads them directly.

ALTER TABLE experiences
    ADD COLUMN company_normalized    text GENERATED ALWAYS AS (regexp_replace(lower(company),              '[^a-z0-9]', '', 'g')) STORED,
    ADD COLUMN role_title_normalized text GENERATED ALWAYS AS (regexp_replace(lower(role_title),           '[^a-z0-9]', '', 'g')) STORED,
    ADD COLUMN level_normalized      text GENERATED ALWAYS AS (regexp_replace(lower(coalesce(level, '')), '[^a-z0-9]', '', 'g')) STORED;

-- GIN trigram indexes on the normalized columns so the '%...%' normalized-contains filters
-- (which have a leading wildcard a btree can't serve) stay index-supported.
CREATE INDEX idx_exp_company_norm_trgm ON experiences USING gin (company_normalized    gin_trgm_ops);
CREATE INDEX idx_exp_role_norm_trgm    ON experiences USING gin (role_title_normalized gin_trgm_ops);
CREATE INDEX idx_exp_level_norm_trgm   ON experiences USING gin (level_normalized      gin_trgm_ops);
