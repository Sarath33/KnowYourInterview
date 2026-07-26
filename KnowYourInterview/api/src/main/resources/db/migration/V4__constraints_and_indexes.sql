-- Phase 5 hardening: extra constraints/indexes, optimistic-lock version columns, and
-- widening of monetary columns to BIGINT. All additive — no data migration needed
-- (INTEGER -> BIGINT widens in place and existing rows keep their values).

-- Idempotency backstop for order creation: at most one Purchase per Razorpay order id.
-- NULLs are allowed to repeat (Postgres treats NULLs as distinct under UNIQUE), which is
-- fine — a purchase only gets an order id once its order has actually been created.
ALTER TABLE purchases ADD CONSTRAINT uq_purchases_razorpay_order_id UNIQUE (razorpay_order_id);

-- Browse lists PUBLISHED experiences sorted by published_at desc; a composite index on
-- (status, published_at) serves that filter + sort together.
CREATE INDEX idx_experiences_status_published_at ON experiences (status, published_at);

-- Optimistic locking (JPA @Version) for concurrent-update safety. DEFAULT 0 so existing
-- rows satisfy the new NOT NULL column without a separate backfill.
ALTER TABLE experiences ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE purchases   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payouts     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Widen monetary amounts (paise) to BIGINT — headroom for higher-value experiences and
-- future tiering, well beyond INT's ~21474836.47 rupee ceiling. Safe in-place widening.
ALTER TABLE experiences ALTER COLUMN price_paise  TYPE BIGINT;
ALTER TABLE purchases   ALTER COLUMN amount_paise TYPE BIGINT;
ALTER TABLE payouts     ALTER COLUMN amount_paise TYPE BIGINT;
