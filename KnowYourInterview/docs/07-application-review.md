# End-to-end application review — 2026-08-01

Read pass over `api/`, `web/`, `shared/`, `docs/`, and the Flyway migrations, before scoping the
next round of enhancements. Ordered by severity, with file/line pointers so each item is
actionable on its own.

> **Status (same day): §1.1–§1.7 and §2.1 are all fixed** — see `08-fixes-2026-08-01.md` for what
> changed and what to check when running it locally. The findings below are kept as written, as
> the record of what was wrong and why; line numbers refer to the code *before* the fixes.
> §3–§6 (product gaps, security notes, code health, doc drift) are still open, apart from the
> handful of stale comments the fixes corrected in passing.
>
> One correction to §5 while I was in there: `ConfirmDialog` already had `role="dialog"`,
> `aria-modal`, a focus trap, focus restore, and Escape-to-close. That review note was wrong.

## What I could and couldn't verify

- **Frontend typecheck: passing.** `tsc -b` on `web/` (with `shared/` alongside) compiles clean.
- **Frontend tests: not run.** The sandbox couldn't install rollup's platform binary
  (`@rollup/rollup-linux-arm64-gnu` is blocked by the registry proxy), so Vitest wouldn't boot.
  Run `npm test` locally.
- **Backend: not compiled or run.** No JDK 21, Maven, or Docker available here (JDK 11 only).
  `mvn verify` locally is still the outstanding item from Phase 5 — everything below is from
  reading the code, not from a failing test.

Everything flagged as a **bug** is reasoned from the code and the schema; the two marked
"needs confirming" are the ones I'd want a local reproduction for before changing anything.

---

## 1. Bugs and correctness issues

### 1.1 Deleting an experience that's ever been viewed fails with a confusing 409
**`ExperienceService#deleteExperience` (api/.../experience/ExperienceService.java:258) +
`V10__experience_views.sql`**

`experience_views.experience_id` references `experiences(id)` with **no `ON DELETE CASCADE`**, and
`deleteExperience` cleans up proof documents, rounds, review logs, and edit snapshots — but never
`experience_views`. Any experience with at least one recorded view can't be deleted; the FK
violation surfaces through `ApiExceptionHandler` as a generic
`409 "That operation conflicts with existing data"`.

Reachable today: a **free contribution** (or an admin **reference submission**) publishes → a
signed-in user opens it → owner unpublishes → owner tries to delete. Neither an entitlement nor a
payout exists, so both existing guards pass and the delete goes straight into the constraint.

Fix is either `experienceViewRepository.deleteByExperienceId(...)` alongside the other deletes, or
`ON DELETE CASCADE` on the FK (views are pure derived analytics — cascading is defensible, unlike
`review_logs`, which is deliberately non-cascading as an audit trail).

### 1.2 View counting can 409 a plain page load under concurrency
**`ExperienceService#getPublicView` (ExperienceService.java:426) + `Experience.version` (`@Version`)**

`getPublicView` is a write transaction: on a viewer's first view it calls
`experience.incrementViewCount()` and saves. `experiences` carries an optimistic-lock `@Version`
column, so **two different signed-in users loading the same experience for the first time
concurrently** will collide — one commits, the other throws
`ObjectOptimisticLockingFailureException` and the viewer gets
`409 "This resource was updated by someone else — please retry"` on what is, to them, a read.

`recordView`'s `ON CONFLICT DO NOTHING` correctly de-dupes the same user, but it does nothing for
two different users. The narrow fix is an atomic `UPDATE experiences SET view_count = view_count + 1`
(a `@Modifying` query that bypasses the versioned entity) instead of a read-modify-write on the
managed entity. It also makes the detail endpoint a pure read again for anyone who has already
viewed it.

### 1.3 Rate limiting on Railway currently throttles everyone as one client
**`RateLimitingFilter` (api/.../security/RateLimitingFilter.java:69)**

The Redis key is `ratelimit:<path>:<request.getRemoteAddr()>`. The code comments explain the
deliberate choice not to trust `X-Forwarded-For` without a trusted-proxy allowlist — correct in
principle, but the app is now *deployed behind Railway's edge*, so `getRemoteAddr()` is Railway's
proxy, not the user. In practice that means **10 logins/minute and 5 registrations/minute across
all users globally**. A modest traffic spike locks legitimate users out of login.

This shifted from "not strict enough" to "actively wrong" when the app deployed. Options:
`ForwardedHeaderFilter` with Railway's edge treated as trusted, or read the leftmost
`X-Forwarded-For` hop specifically for the rate-limit key while keeping everything else on
`getRemoteAddr()`.

### 1.4 Round types render as raw enum values to paying viewers
**`ExperienceDetail.tsx:290**

The detail page renders `Round {round.roundNumber} — {round.roundType}`, i.e. `SYSTEM_DESIGN`,
`ONSITE_BAR_RAISER`, `TAKE_HOME`. The `roundTypeLabel` mapping exists but lives inside
`SubmissionWorkspace.tsx` and is only used there. So the contributor writing the submission sees
"System design" and the viewer who just paid ₹99 sees `SYSTEM_DESIGN`. Lift `ROUND_TYPES` /
`roundTypeLabel` into a shared module (`lib/format.ts` is the natural home) and use it in both.

### 1.5 Admin approves content they can't actually read
**`AdminReviewQueue.tsx:136-138`**

The review queue card shows company, role, teaser, source/confidential notes, proof links — and
then `{exp.rounds.length} round(s), {exp.proofDocuments.length} proof document(s)`. **The rounds
themselves are never rendered.** The questions, approach, and interviewer notes — the entire
substance being verified and paid for — aren't on screen at the moment the admin clicks
"Approve & publish".

`docs/05-capabilities.md` claims admins "Open any pending experience and see everything the
contributor submitted: all fields, every round". That isn't true of the UI. The data is already in
the payload (`ExperienceFullResponse` includes rounds), so this is a rendering gap, not an API one.

Related: "Approve & publish" — which publishes content, stamps a price, and creates a payout
liability — fires on a single click with no confirmation, while a contributor deleting their own
draft gets a modal.

### 1.6 Password reset has no user-facing path at all
**`AuthService#forgotPassword/#resetPassword` exist; `web/` has nothing**

`POST /api/v1/auth/forgot-password` and `/reset-password` are implemented, rate-limited, and
token-hashed. But there is **no "Forgot password?" link, no reset screen, no route, and no client
function** — `grep` for `forgot|reset-password` across `web/src` and `shared/types.ts` returns
nothing, and `parseRoute` (App.tsx:52) has no `/reset-password` case even though
`AuthService` logs a link pointing at exactly that path.

So a user who forgets their password today is permanently locked out, independent of the known
"no email provider" gap. The docs list the email provider as the blocker; the missing UI is the
larger half and doesn't depend on it (you can ship the screens and hand out the logged link
manually, or wire Postmark at the same time).

### 1.7 Negative pagination params 500
**`ExperienceController#browse` (:141) → `browsePublished` (ExperienceService.java:384)**

`size` is clamped with `Math.min(size, maxPageSize)` but has no lower bound, and `page` isn't
bounded at all. `PageRequest.of(-1, 20)` throws `IllegalArgumentException` → caught by the
catch-all handler → `500 "Something went wrong"` for what should be a `400`. One-line fix
(`Math.max(0, page)`, `Math.clamp(size, 1, maxPageSize)`).

---

## 2. Dead code

### 2.1 `web/src/components/SubmissionWorkspace/` — 1,179 lines, never bundled

There are two implementations of the submission workspace:

| | lines | reachable? |
|---|---|---|
| `components/SubmissionWorkspace.tsx` | 1,600 | **yes** |
| `components/SubmissionWorkspace/` (8 files) | 1,179 | **no** |

Node/Vite/TypeScript resolution prefers the file over the directory, so
`import { SubmissionWorkspace } from "./components/SubmissionWorkspace"` resolves to the
monolithic `.tsx`. Verified with `tsc --traceResolution`:

```
Module name './components/SubmissionWorkspace' was successfully resolved to
'.../src/components/SubmissionWorkspace.tsx'
```

`AdminReviewQueue.tsx` importing `EditDetailsForm` from the same specifier confirms it — the
directory's `index.ts` only exports `SubmissionWorkspace`, so that import would fail if the
directory were winning.

This looks like a refactor (split the 1,600-line component into `SubmissionWorkspace/`,
`AddRoundForm`, `RoundCard`, `SubmissionDetail`, `types`) that was written but never landed,
because deleting the old file was the missing last step. It typechecks and is covered by the
tests **that import through the same ambiguous specifier**, so nothing catches it.

Two options: finish the refactor (delete `SubmissionWorkspace.tsx`, re-export `EditDetailsForm`
from the directory's `index.ts`, diff the two for behavior that only exists in the monolith), or
delete the directory. Either way this shouldn't stay as-is — a future edit has a coin-flip chance
of landing in the file nobody runs.

---

## 3. Product gaps worth considering

Not defects — deliberate scope, but the ones I'd rank highest for the next pass:

1. **Notifications (still the biggest hole).** A contributor learns their submission was approved,
   rejected, sent back for correction, or paid only by logging in and looking. Same absent email
   infrastructure blocks password reset (§1.6) — one integration unblocks both.
2. **Free experiences never enter My Library.** The library is built from `purchases`, so a viewer
   who read a free contribution or a reference submission has no way back to it except re-finding
   it in Browse. A "saved/read" concept, or unioning free views into the library, would close it.
3. **No admin surface for published content.** Unpublishing requires finding the listing through
   Browse like a viewer would. No moderation queue, no `PUBLISHED`-scoped search.
4. **The review log is write-only.** `review_logs` has recorded every approve/reject/correction
   since Phase 3, and nothing reads it — an audit view is close to free.
5. **No refund or entitlement-reversal path.** A disputed purchase has no in-app resolution.
6. **Contributor analytics stop at raw counts.** Unlock count and view count exist; no earnings
   total, no trend.
7. **Payout status model is half-wired.** `PROCESSING` and `FAILED` exist in the schema and in
   `Payout`, but nothing sets or reads them; there's no un-mark, no reference edit, no queue
   filter, no total-liability figure.

---

## 4. Security and ops notes

Overall this is in good shape — BCrypt, rotating single-use refresh tokens in Redis, constant-time
bootstrap-secret comparison, path-traversal guard in `LocalProofStorageService`, content-type
allow-list on uploads, bound JPA parameters throughout, sane Spring Security defaults, no secrets
committed. The items below are smaller than §1.

- **Password reset doesn't revoke sessions.** `AuthService#resetPassword` (:194) changes the hash
  but leaves every `refresh:<jti>` key in Redis alive. A user resetting *because* they think
  they're compromised stays compromised for up to 30 days. Deleting that user's refresh keys on
  reset (a `SCAN`, or a per-user refresh-token index) closes it.
- **No change-password for a logged-in user.** Only the forgot-password flow — which itself has no
  UI (§1.6).
- **Tokens in `localStorage`** — already acknowledged in `authStorage.ts`; worth revisiting before
  this holds meaningful volumes of real user data (httpOnly refresh cookie).
- **Proof documents are sensitive PII on a single Railway volume.** Already in the docs as a known
  gap; it's also the item that blocks running more than one `api` replica. Item #3 of the
  "Open items to resolve" list in `04-handoff.md` (retention/deletion policy) is still unaddressed.
- **`getPublicView` returning `403 → NotFound`** for non-visible experiences is correct
  (no existence leak) — worth keeping in mind if that logic gets refactored.
- Deployment gaps carried from `06-deployment.md` and still open: Razorpay keys unset in prod,
  webhook never exercised against real Razorpay, `SENTRY_DSN` unset, Google Sign-In unset, no
  custom domain, Redis unauthenticated on the private network.

---

## 5. Code health

The backend is genuinely good: services are cohesive, the transactional boundaries are thought
through (the compensating file-delete-on-rollback in `ExperienceService` and keeping the Razorpay
call *out* of a DB transaction in `PurchaseService` are both the right calls and are rare to see),
the exception handler is complete, and the comments explain *why* rather than *what*. Test coverage
is real: ~4,000 lines of tests including two Testcontainers `*IT` suites.

Frontend is more uneven:

- **Two styling systems.** `App.css` has a full design-token system and utility classes, and the
  components then layer hundreds of inline `style={{}}` objects on top (51 in
  `SubmissionWorkspace.tsx` alone, 24 in `ExperienceDetail.tsx`). Anything spacing- or
  typography-related has to be changed in two places.
- **`SubmissionWorkspace.tsx` is 1,600 lines** and holds create-draft, edit-details, rounds, proof
  upload, edit history, delete confirmations, and autosave. The dead directory in §2.1 is exactly
  the refactor this needs.
- **Mobile:** three media queries total (`max-width: 560px`, `720px`). The Browse filter bar is six
  fixed-pixel-width inputs in a flex row (260/180/180/140/110px + two buttons) — that wraps rather
  than adapts, and the admin queue and submission rail have similar fixed widths. Worth a real
  responsive pass if any meaningful share of traffic is phones (likely, for this audience).
- **Accessibility:** `aria-label` appears on Browse's inputs and `DropdownMenu`, and loading states
  use `aria-busy`/`aria-live` consistently — a good baseline. But the admin queue's inputs have
  placeholders and no labels, `ConfirmDialog` needs a focus trap and `role="dialog"` check, and
  there's no visible-focus audit.
- **Inconsistent data-loading patterns.** `useAsync` is used by Browse, ExperienceDetail,
  MyLibrary, AdminReviewQueue — but `SubmissionWorkspace` still hand-rolls
  `loading`/`error`/`load()`, which is exactly the pattern `useAsync` was introduced to replace
  (and which had the out-of-order race the comments mention).
- **`ExperienceDetail`'s back button always reads "Back to browse"** even when opened from
  `/library/:id`, where it correctly navigates back to the library.

---

## 6. Documentation drift

`docs/` is unusually thorough, but it's now behind the code in a few places:

- **`04-handoff.md` stops at Phase 5.** Everything since — Google Sign-In, correction-requested,
  confidential notes, edit history, free contributions, reference submissions, view counting, real
  URL routing, admin bootstrap, migrations V4–V10 — is only described in `05-capabilities.md`,
  and there as a single ~1,500-word paragraph of "a fourteenth pass added…". That paragraph has
  become the de facto changelog and is no longer readable as one. Worth splitting into a real
  `CHANGELOG.md` with dated entries.
- **`Experience.viewCount`'s Javadoc is stale** — still says "Raw hit counter, not deduped by
  viewer/session — every load counts", which V10 and `ExperienceView` changed to one-per-user.
  `V9__views_correction_confidential.sql`'s comment has the same stale claim.
- **`ExperienceService#deleteExperience`'s Javadoc** says the window is "DRAFT, PENDING_REVIEW, or
  REJECTED" but `requireContentEditable` also allows `CORRECTION_REQUESTED`.
- **`api.ts:256`** — `deleteExperience` is documented "Draft or rejected only", two widenings out
  of date.
- **`05-capabilities.md` overstates the admin review UI** (see §1.5).
- **`README.md`'s admin instructions** point at the SQL `UPDATE`, not the newer
  `bootstrap-admin` endpoint.

---

## 7. Suggested order for the next pass

If it were me, roughly:

1. §1.1 view-delete FK, §1.2 optimistic-lock 409, §1.7 pagination bounds — small, contained,
   correctness.
2. §1.3 rate limiting behind the proxy — currently degrading production login.
3. §2.1 resolve the duplicate SubmissionWorkspace before writing any new frontend code in it.
4. §1.4 round-type labels and §1.5 the admin review queue showing rounds — both directly affect
   the two people the product depends on (the paying viewer and the reviewer).
5. Email provider → password reset UI (§1.6) + approval/rejection notifications (§3.1) as one
   piece of work.
6. Responsive/accessibility pass; docs reconciliation.
