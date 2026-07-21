-- Phase 4 (payout half): manual-batch payout marking. RazorpayX needs a separate
-- Current Account with its own business KYC approval — not available yet — so for
-- now an admin transfers the money themselves (bank/UPI) and records it here rather
-- than the app calling a live RazorpayX Payout API. See docs/04-handoff.md.

ALTER TABLE payouts
    ADD COLUMN paid_by_admin_id UUID REFERENCES users(id),
    ADD COLUMN payout_reference VARCHAR(255);
