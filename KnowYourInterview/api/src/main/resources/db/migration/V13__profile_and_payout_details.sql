-- Profile / account-management support.
--
-- deleted_at tombstones a self-deleted account (see User#anonymizeForDeletion). It is left
-- nullable and without a default: a live account simply has no value here, and only the
-- self-delete path ever stamps it. The row is retained rather than hard-deleted because
-- experiences, payouts and purchases all reference users(id).
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;

-- The contributor's UPI VPA (e.g. name@bank), collected on the payout-account form so an
-- admin knows where to wire the manual payout. Nullable: the payout_accounts row predates
-- this column, and account_holder_name can exist without a VPA on file yet.
ALTER TABLE payout_accounts ADD COLUMN upi_vpa VARCHAR(255);
