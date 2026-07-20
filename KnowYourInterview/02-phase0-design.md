# Know Your Interview — Phase 0 Design

_Product design & MVP spec. Stack: Java 21 · Spring Boot 4.1 · React 19 (web) · Expo/React Native (mobile) · PostgreSQL · AWS · Razorpay (India)._

---

## 1. Product in one line

A verified marketplace for structured interview experiences: **contributors** submit their real interview experience, get it verified by an admin, and receive a one-time flat fee on publish; **candidates** pay per experience to unlock and read them.

### Confirmed business rules
- **Contributor payout:** one-time flat fee, paid after admin verifies & publishes.
- **Viewer pricing:** pay-per-experience (unlock one at a time).
- **Price setting:** platform-fixed (can be tiered by company/role later).
- **Verification:** contributor uploads proof (offer/reject email, interview invite, LinkedIn); admin reviews and approves/rejects.
- **Market:** India first — Razorpay for payments, RazorpayX for payouts, INR (paise).

---

## 2. Roles

| Role | Can do |
|---|---|
| **Guest** | Browse listings (title, company, role, price, teaser) — cannot read full content. |
| **Contributor** | Submit experiences, upload proof, track verification status, receive payout, set up payout method. |
| **Viewer/Candidate** | Purchase & read experiences, view purchase history. |
| **Admin** | Review submissions + proof, approve/reject, trigger payout, manage catalog, moderate. |

A single **User** can act as contributor and viewer. Admin is a role/flag.

---

## 3. Core flows

### 3.1 Contributor submission → publish
1. Contributor fills the structured form (§5) and uploads proof file(s).
2. Submission saved as `PENDING_REVIEW`. Proof stored privately (S3, not publicly served).
3. Admin reviews content + proof → `APPROVED` or `REJECTED` (with reason).
4. On approve → `PUBLISHED` and listed; platform initiates the one-time payout.
5. Contributor notified at each state change.

### 3.2 Viewer purchase → read
1. Viewer browses listings (public teaser only).
2. Clicks unlock → pays the fixed price via Razorpay.
3. On confirmed payment (Razorpay webhook), an **Entitlement** is created (user ↔ experience).
4. Viewer reads the full content on web and mobile, forever.
5. Re-visiting checks the entitlement — no re-charge.

### 3.3 Payout (contributor)
- MVP-simple: payout = ledger record + RazorpayX transfer (or manual batch for the very first version).
- Contributor must have a payout method on file before funds release.

---

## 4. Data model (ERD)

```
User ──1:N── Experience (as contributor)
User ──1:N── Entitlement ──N:1── Experience
User ──1:N── Purchase
Experience ──1:N── ProofDocument
Experience ──1:N── ExperienceRound
User ──1:1── PayoutAccount
Experience ──1:1── Payout
Admin actions ──▶ ReviewLog
```

Key entities (see `api/src/main/resources/db/migration/V1__init.sql` for the exact schema): **User, Experience, ExperienceRound, ProofDocument, Purchase, Entitlement, PayoutAccount, Payout, ReviewLog.** Amounts stored in paise; payment references use Razorpay order/payment/payout IDs.

---

## 5. The structured experience form

The product's core differentiator. Contributor provides:
- **Company**, **Role/title**, **Level**, **Location/remote**, **Month & year**, **Outcome**.
- **Rounds** (repeatable): round type, duration, questions asked, topics/tags, your approach, interviewer behavior, difficulty.
- **Overall**: prep advice, overall difficulty, timeline, compensation (optional).
- **Teaser**: 1–2 public sentences (no question specifics).

Structured fields (not free-text blobs) make listings searchable/filterable and keep quality consistent.

---

## 6. MVP API contract (REST, `/api/v1`)

**Auth:** register, login, refresh, logout, password-reset.
**Contributor:** create/edit draft, add/edit/delete rounds, upload proof, submit for review, list my submissions.
**Browse/public:** list experiences (teasers, filterable by company/role/level/year), get one (full only if entitled, else teaser + price).
**Purchase:** create Razorpay order, webhook to confirm → Entitlement + Purchase=PAID, my purchases, my library.
**Payout:** onboard payout account, list my payouts.
**Admin:** review queue, view full + proof, approve (→ publish + payout), reject (with reason).

---

## 7. MVP scope — in vs. out

**In (v1):** auth & accounts · structured submission + proof upload · admin review queue & approve/reject · publish & browse with teasers · pay-per-experience unlock (Razorpay) · entitlement-gated reading · one-time contributor payout · basic search/filter · web + mobile for read/purchase.

**Out (later):** ratings/reviews · per-view royalties · subscriptions/credits · contributor-set pricing · automated proof verification · comments/Q&A · referrals · company pages · analytics dashboards · i18n.

---

## 8. Risks & things to resolve _(not legal advice — I'm not a lawyer; get a professional opinion on starred items)_

1. **★ Legal / NDA risk (highest priority).** Many interviews are covered by NDAs/confidentiality; paying people to disclose company interview questions could create liability for the platform and contributors. Get a lawyer's review before launch, plus clear ToS, a content policy (no verbatim proprietary/take-home material), and a takedown process.
2. **★ Payments & tax.** Paying contributors makes you a marketplace — KYC, GST/tax reporting (India), RazorpayX onboarding, refund/chargeback policy.
3. **Proof handling = sensitive PII.** Store privately, encrypt, restrict to admins, define retention/deletion. GDPR applies for EU users.
4. **Fraud/quality.** Fake or plagiarized experiences; define rejection criteria and duplicate detection.
5. **Cold-start.** No content = no buyers. Seed content; launch a niche first (e.g. SWE at big tech).
6. **Pricing calibration.** Fixed price is simple; keep it centrally changeable.

---

## 9. Next actions

1. Resolve the ★ starred risks — especially legal/NDA.
2. Set up your personal machine and run the Phase 1 skeleton (see `03-setup-guide.md`).
3. Confirm health endpoint returns `UP`, then start Phase 2 (authentication).
