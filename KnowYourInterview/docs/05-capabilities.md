# Contributor & Admin Capabilities — Current State

_Written July 2026 as a review baseline before scoping feature enhancements. Reflects what's actually implemented in `api/` and `web/` today — not the roadmap, not future plans._

_Updated the same month: three of the limits below (resubmission after rejection, editing/unpublishing live content, deleting drafts/rounds/proof) were closed. See the "Editable window" note under Contributor and the "Unpublish" note under Admin for how they work now._

## A note on roles

There's no separate "contributor account" or "viewer account" — every registered user is the same kind of account, gated only by a single `isAdmin` boolean on `users`. Any signed-in user can both submit their own interview experiences (acting as a contributor) and unlock/read other people's published experiences (acting as a viewer). "Contributor" below just means the set of actions any regular user has when working with their own submissions; those same users also have the viewer capabilities listed at the end. Admin is the one real second tier — a flag an operator sets manually via a direct DB update, not something a user can request or be granted through the product.

---

## Contributor capabilities

### Account
- Register with email, password, and display name; log in/out; refresh session automatically via a rotating refresh token.
- Request a password reset (currently logs the reset link server-side rather than emailing it — no email provider is wired up yet, see Known limits below).
- Browse published experiences without an account at all ("Browse without an account" on the login screen) — but submitting or unlocking requires signing in.

### Submitting an experience
- Create a draft interview write-up: company, role title, level, location, remote flag, interview month/year, outcome (Offer / Rejected / Withdrawn), a public teaser (1-2 sentences, no question specifics), prep advice, overall difficulty (1-5), timeline, and compensation.
- **Editable window:** a draft (never submitted) and a rejected experience are both fully editable — edit any field, add/remove rounds, upload/delete proof documents, or delete the whole submission. Once it's `PENDING_REVIEW` (submitted, awaiting a verdict) or `PUBLISHED` (live), it's locked from direct edits — a published one has to be unpublished first (see below).
- Add interview rounds, each with: round type (free text — "PHONE_SCREEN", "ONSITE", "SYSTEM_DESIGN", etc.), duration, questions asked, topic tags, their approach, interviewer behavior, and difficulty (1-5). Rounds are numbered automatically in the order added.
- Remove a round, or delete a proof document outright — both work any time the experience is in the editable window.
- Upload proof documents (offer letters, interview invites, etc.). Multiple files supported; each upload is a separate record.
- Delete an entire draft or rejected submission — cascades to its rounds and proof documents (including the stored files, not just the DB rows).
- View their own proof documents in the submission workspace (filename shown; contributors still don't get a "view/open" link on their own docs the way admins do on the review queue — only delete-and-reupload if something's wrong).
- Submit (or **resubmit**, if it was rejected) for admin review — blocked unless it has **at least one round and at least one proof document**. Resubmitting clears the old rejection reason so a fresh review doesn't show last time's verdict.
- **Unpublish their own live experience** to fix something — pulls it from Browse and drops it back to draft, where it's editable again; it has to go through submit → admin approval again before it's public. Anyone who already unlocked it keeps full access throughout — unpublishing never revokes an existing purchaser's entitlement.
- See every experience they've ever submitted, in any state (draft, pending review, approved, rejected, published), with the platform-set price shown once published.
- See the specific rejection reason if an admin rejects their submission, with a clear path to fix it and resubmit.
- Does **not** set the price — the platform applies a fixed default price to every submission at draft-creation time; contributors have no pricing input.

### Getting paid
- See every payout owed to them (one row is created automatically the moment an admin approves their experience) with status: Pending, Processing, Paid, or Failed.
- See the amount, which experience it's for, and — once paid — the date it was marked paid.
- Cannot see *who* marked it paid or any reference/transaction ID the admin recorded (contributors get a trimmed view; that detail is admin-only, by design, to avoid leaking internal reconciliation notes).
- Cannot request a payout, dispute a payout status, or provide their own bank/UPI details in-app — the whole flow is manual: an admin wires the money outside the app (bank transfer/UPI) using contact details from wherever it happens (not the app), then marks the row paid. There's no in-app payout-details form at all yet.

### Reading others' work (viewer side, same account)
- Browse all published experiences, filterable by company, role title, level, and year, paginated.
- See the free teaser (company, role, level, location, outcome, remote flag, and the short public blurb) for any published experience without paying.
- Pay to unlock the full write-up (all rounds, questions asked, prep advice, timeline, compensation, difficulty) via Razorpay Checkout — one-time per experience, tied to their account permanently once paid.
- See everything they've unlocked in "My library."
- Automatically see their own full content even before anyone pays (they wrote it) and never get charged to view their own submissions.

### Known limits worth knowing about before scoping enhancements
- No notifications — a contributor finds out their experience was approved/rejected/paid for only by checking the app; nothing is emailed or pushed.
- No visibility into how many people have unlocked their experience, or any earnings/performance analytics.
- No way to provide payout bank/UPI details in-app; that exchange happens entirely outside the product.
- Proof documents can't be viewed/opened post-upload (no "view" affordance in the submission workspace, unlike the admin review queue) — only deleted and re-uploaded.
- No draft auto-save/versioning — editing a rejected experience overwrites in place; there's no history of what changed between rejection and resubmission.
- Unpublishing sends an experience all the way back to draft and through a full re-review, even for a one-character typo fix — there's no lightweight "edit without losing PUBLISHED status" path. Deliberate trade-off for a verified marketplace (see the Admin section's note on why), but worth knowing if it turns out to be too much friction for small corrections.

---

## Admin capabilities

Gated by a single `isAdmin` flag on the user row (set directly in the database — there's no promote/demote UI). Everything below requires `ROLE_ADMIN`.

### Reviewing submissions
- See the full review queue: every experience in `PENDING_REVIEW` status, oldest first.
- Open any pending experience and see everything the contributor submitted: all fields, every round, every proof document.
- View (open/download) any proof document on a pending submission.
- Approve a submission — this publishes it immediately at the platform's default price, stamps a `publishedAt` timestamp, logs the approval (who approved it, when), and automatically creates a Pending payout ledger row for the contributor's flat fee. One click does all four of those things; there's no separate "publish" step or price-override at approval time.
- Reject a submission with a required reason — the contributor sees that exact reason. No payout row is created for a rejection.
- Every approve/reject action is logged (`review_logs`: admin id, action, reason, timestamp) — but there's currently no UI to view that log; it's write-only from the product's perspective today.
- Can view **any** experience's full content regardless of status or ownership (draft, pending, published, rejected) — admins bypass the entitlement/purchase gate entirely, for review purposes.
- **Unpublish any live experience**, regardless of who owns it — content moderation power independent of the contributor's own self-service unpublish. Pulls it from Browse and drops it to draft; the same re-review cycle applies before it can go live again. The payout ledger row created at the original approval is untouched either way (money already moved, or is still owed, independent of whether the listing is currently live) — unpublishing is not the same as reversing a payout, and there's still no in-app way to do that (see Known limits).

### Payouts
- See every payout that's Pending or Processing, oldest first, with the contributor's name, email, the experience it's for, and the amount owed.
- Mark a payout as paid, optionally attaching a free-text reference (a UPI transaction ID, bank reference, whatever helps them reconcile later).
- Cannot mark a payout as Processing or Failed from the UI — those statuses exist in the data model but nothing in the product currently sets or uses them (Pending → Paid is the only transition actually wired up).
- Cannot un-mark a payout as paid, edit a reference after the fact, or filter/search the payout queue.
- Has no visibility into total payout liability, month-over-month spend, or anything resembling a finance dashboard — just a flat queue.

### Everything a regular user can do
- Admins are still regular users underneath — they can also submit their own experiences, browse, and unlock/purchase others' content, all with the same UI as anyone else (plus the two admin-only tabs).

### Known limits worth knowing about before scoping enhancements
- No user management at all: can't view the user list, promote/demote admins, disable/ban accounts, or see who's registered — from inside the app, an admin's only levers are the review queue, the payout queue, and now unpublish.
- No admin-side surface for finding a live experience to unpublish, other than Browse itself — there's no moderation queue or search scoped to `PUBLISHED` content; an admin has to go find the listing the same way a viewer would, then unpublish from its detail page.
- No analytics/dashboard: no revenue totals, no purchase counts, no contributor leaderboard, no funnel (drafts → submitted → approved → published → purchased).
- No audit UI for the review log that's already being recorded in the database.
- No way to adjust an experience's price after publishing (or set a non-default price at approval time).
- No bulk actions — approve/reject/mark-paid/unpublish are all one row at a time.
- No refund tooling — if a viewer disputes a purchase, there's no in-app way for an admin to reverse an entitlement or issue a refund.

---

## Quick reference: who can do what

| Capability | Contributor (own content) | Any viewer | Admin |
|---|---|---|---|
| Submit / edit / delete a draft or rejected submission | Yes (own only) | — | — (unless also a contributor) |
| Add/remove rounds, upload/delete proof | Yes, editable states only | — | — |
| Submit / resubmit for review | Yes | — | — |
| Approve / reject submissions | No | No | Yes |
| Unpublish a live experience | Yes (own only) | No | Yes (any) |
| See full content pre-purchase | Own content only | No (teaser only) | Yes, anything |
| Purchase/unlock | Yes (others') | Yes | Yes |
| Keep access after an unpublish | Yes, if already purchased | Yes, if already purchased | Yes, always |
| See own payouts | Yes | — | — |
| Mark payouts paid | No | No | Yes |
| Manage users / roles | No | No | No (not built yet) |
