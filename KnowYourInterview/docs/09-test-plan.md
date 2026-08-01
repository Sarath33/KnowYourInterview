# Test plan & test-case suite — Know Your Interview

Owner: QA. Written 2026-08-01, against the code as it stands after
[`08-fixes-2026-08-01.md`](08-fixes-2026-08-01.md).

This document is the *design*. The functional tests that implement it live in
`api/src/test/java/com/knowyourinterview/api/functional/` — see §8 for the case-ID →
test-method traceability map.

---

## 1. Scope and objectives

**In scope**

- The Spring Boot REST API (`api/`) — every endpoint under `/api/v1/**`, plus `/actuator/health`.
- Business rules that decide **who can see what** and **who gets paid**, which is where the
  money and the legal exposure sit.
- The real infrastructure the API depends on: PostgreSQL (Flyway-migrated schema, JPQL
  queries, constraints) and Redis (refresh-token revocation, rate limiting).

**Out of scope for this pass** (called out explicitly so nobody assumes coverage that isn't there)

- Browser-driven E2E of the React app. The web app has a Vitest/RTL component suite;
  nothing drives a real browser. See §9.
- Live Razorpay API calls. The functional suite is **hermetic** — no outbound network. See §6.3.
- Live Google Sign-In against Google's JWKS. The verifier is stubbed at the port. See §6.4.
- Load/performance, RazorpayX payouts (not built), mobile (not built).

**Objectives, in priority order**

1. No unauthorized read of paid or private content (paywall, proof documents, confidential notes).
2. No incorrect money outcome (double entitlement, double payout, payout for a free submission,
   unlock without a verified signature).
3. Every state transition in the submission lifecycle behaves per the design, including the
   unhappy paths.
4. The API's error contract is stable — right status code, right shape.
5. The schema and the JPA/JPQL layer actually work against real Postgres, not just against mocks.

---

## 2. Test layers, and what belongs in each

The repo already has three layers. The purpose of this plan is to fill the third, which was
thinnest.

| Layer | Tooling | Runs on | What belongs here |
|---|---|---|---|
| **Unit** | JUnit 5 + Mockito, no Spring | `mvn test` | Pure branching logic in services. Already strong: `AuthServiceTest`, `ExperienceServiceTest` (1.6k lines), `AdminReviewServiceTest`, `PurchaseServiceTest`, `PayoutServiceTest`, `JwtServiceTest`, `RateLimitingFilterTest`. |
| **Slice** | `@WebMvcTest` + mocked service | `mvn test` | Routing, JSON binding, validation, and the `SecurityConfig` rule that guards each route. Already present for every controller. |
| **Functional (FT)** | `@SpringBootTest(RANDOM_PORT)` + Testcontainers + `TestRestTemplate` | `mvn verify` | **This plan.** Black-box HTTP against the real stack: real Postgres, real Redis, real Flyway schema, real security filter chain, real transactions. |

**The rule for adding a case:** if it can be proven with a mock, it belongs in a unit test. It
belongs in an FT only if it depends on something a mock would have faked away — a real SQL
constraint, a real JPQL query plan, a real Redis TTL, the real filter chain ordering, a real
transaction boundary, or the interaction of two modules.

### What the FT layer specifically adds over what already exists

The pre-existing `AuthFlowIT` and `PurchaseFlowIT` cover one happy path each. Everything below
is uncovered at the real-stack level before this plan:

- Browse: the `browsePublished` JPQL with its `CAST(:param AS string)` null-filter branches,
  pagination clamping, and the three sort modes. This query is mock-proof — it can only fail
  against a real Postgres planner, which is exactly the bug class the `CAST` comments describe.
- Every lifecycle transition other than draft → submit → approve: reject → resubmit,
  correction-requested → resubmit, unpublish → re-review, delete guards.
- The free-contribution path (publishes without review) and the admin reference-submission path.
- Proof document upload/download authorization, content-type rejection, and disk/DB consistency.
- `confidentialNote` redaction for a paying viewer.
- View counting semantics (one per signed-in viewer, guests uncounted).
- The webhook path end-to-end with a real HMAC signature.
- The full authorization matrix — 401 vs 403 vs 404 on every route, and IDOR across users.
- Password reset end-to-end, including single-use and expiry, against real rows.

---

## 3. Risk ranking

Priorities on every case below come from this table. It is the reason the suite is ordered the
way it is.

| # | Risk | Impact | Where it would surface | Priority of related cases |
|---|---|---|---|---|
| R1 | Paid content readable without paying | Revenue loss; product is pointless | `getPublicView`, browse teaser, `ExperienceViewResponse` | **P0** |
| R2 | Proof documents (PII: offer letters, IDs) leak to a non-owner | Privacy incident; open item #3 in the handoff | `downloadProof` | **P0** |
| R3 | Entitlement granted without a verified payment signature | Revenue loss, fraud | `confirmPayment`, webhook | **P0** |
| R4 | Double entitlement or double payout | Direct money loss | `grantEntitlement`, `markPaid` | **P0** |
| R5 | Privilege escalation to admin | Full compromise (approve, publish, pay out) | `SecurityConfig`, `bootstrap-admin`, JWT `admin` claim | **P0** |
| R6 | `confidentialNote` shown to a purchaser | Contributor betrayed; NDA exposure (open item #1) | `getPublicView` redaction | **P0** |
| R7 | Refresh token replayable after rotation/logout | Session hijack survives logout | Redis `jti` tracking | **P1** |
| R8 | Lifecycle transition lets content go live unreviewed | Legal exposure (NDA content unvetted) | `submitForReview`, `approve` | **P1** |
| R9 | Contributor loses data or can't recover an account | Churn, support load | delete guards, password reset | **P1** |
| R10 | Browse returns wrong/leaky results | Wrong content shown; unpublished visible | `browsePublished` | **P1** |
| R11 | Rate limiter bucketed wrong behind a proxy | Global lockout of all users | `RateLimitingFilter` | **P1** |
| R12 | Error contract drift (500s where 4xx expected) | Broken client UX, noisy alerting | `ApiExceptionHandler` | **P2** |

---

## 4. Test environment

| | |
|---|---|
| Command | `cd api && ./mvnw verify` |
| Prerequisites | JDK 21, Maven wrapper, **Docker running** |
| Postgres | `postgres:17-alpine` via Testcontainers, Flyway-migrated from `V1` to `V10` on startup |
| Redis | `redis:7-alpine` via Testcontainers |
| Fast subset | `./mvnw test` — unit + slice only, no Docker |
| Single FT class | `./mvnw verify -Dit.test=PurchaseFunctionalIT` |

Containers are **singletons shared across every FT class** (`SharedContainers`), started once
per JVM and torn down by Ryuk at exit. The pre-existing `AuthFlowIT` / `PurchaseFlowIT` keep
their own per-context containers via `ContainerConfig` and are left untouched.

---

## 5. Test data strategy and isolation

| Concern | Approach |
|---|---|
| Test independence | `@BeforeEach` truncates every application table (`users`, `experiences`, `experience_rounds`, `proof_documents`, `experience_edit_snapshots`, `experience_views`, `purchases`, `entitlements`, `payouts`, `payout_accounts`, `review_logs`, `password_reset_tokens`) with `CASCADE`, and flushes Redis. `flyway_schema_history` is never touched. Every test starts from an empty database. |
| Users | Built via the real `POST /api/v1/auth/register` — no direct row inserts, so registration itself is exercised constantly. Emails are unique per call. |
| Admins | Registered, then promoted via `POST /api/v1/auth/bootstrap-admin` (the real endpoint) and re-logged-in, because the `admin` claim is baked into the JWT at issue time. |
| Rate-limit interference | The suite runs with `app.rate-limit.trust-forwarded-for=true` and every helper request carries a **unique `X-Forwarded-For`**, so ordinary fixture setup can't exhaust the 5-registrations-per-minute bucket. Rate-limit cases pin a fixed `X-Forwarded-For` deliberately. This is also, incidentally, direct coverage of the §1.3 proxy-IP fix. |
| Proof files | Written to a per-JVM temp dir (`app.storage.proof-dir`), not `api/uploads/`. |
| Execution order | None assumed. No `@TestMethodOrder` anywhere; any test can run alone. |

---

## 6. Deliberate stubs, and what each one costs in coverage

### 6.1 Razorpay order creation — not exercised
`app.razorpay.key-id` is left **blank** in the FT profile. `createOrder`'s guard checks
(not published / free / already entitled / unknown) all run *before* the missing-key check, so
they are fully covered; the final call to Razorpay's Orders API is never reached and no network
call is made. **Not covered:** the SDK call itself and its `UpstreamServiceException` mapping —
those stay on `PurchaseServiceTest` (mocked) and on manual Test Mode verification.

### 6.2 Payment confirmation — fully exercised, signature and all
Purchases are inserted directly at `CREATED`, exactly as they'd exist right after a real order,
and the signature is computed with `com.razorpay.Utils.getHash` against a known test secret.
`confirmPayment`'s real verification path runs for real. Same for the webhook, whose signature
is `Utils.getHash(rawBody, webhookSecret)` — matching what `Utils.verifyWebhookSignature` checks.

### 6.3 Hermeticity
No FT makes an outbound network call. This is a hard constraint: a test suite that talks to
Razorpay or Google is a suite that fails on a plane, in CI without egress, and on the day the
third party has an incident.

### 6.4 Google Sign-In — stubbed at the port
`GoogleIdTokenVerifierPort` is replaced with `StubGoogleIdTokenVerifier`, which decodes a
fake token of the form `valid:<sub>:<email>:<name>`, throws `InvalidCredentialsException` for
anything else, and `GoogleAuthNotConfiguredException` for the literal token `unconfigured`.
This covers all three of `AuthService.googleLogin`'s resolution branches (new account /
link-to-existing-email / returning user) against real rows. **Not covered:** real JWKS signature,
issuer, audience and `email_verified` checking — that is `GoogleSignInVerifier`'s job and it is
the one class the FT suite treats as a boundary.

---

## 7. Test-case suite

Legend — **P0** must pass before any deploy; **P1** must pass before a release;
**P2** quality bar. `Risk` maps to §3.

### 7.1 Authentication — `FT-AUTH` (`AuthFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-AUTH-01 | Register issues a usable session | No account for the email | `POST /auth/register` | `201`; body has `accessToken`, `refreshToken`, `user.isAdmin=false`; the access token authenticates `GET /experiences/mine` | P1 | R7 |
| FT-AUTH-02 | Password is never stored in cleartext | Registered user | Read `users.password_hash` via JDBC | Hash is not the raw password and starts with `$2` (BCrypt) | P0 | R5 |
| FT-AUTH-03 | Duplicate email rejected | Email already registered | Re-register same email, different case | `409`, and only one `users` row exists | P1 | — |
| FT-AUTH-04 | Email match is case-insensitive on login | Registered as `Foo@x.com` | Login as `foo@x.com` | `200` | P2 | — |
| FT-AUTH-05 | Wrong password rejected without enumerating | Registered user | Login with wrong password; login with unknown email | Both `401`, both message `Invalid email or password` | P1 | R5 |
| FT-AUTH-06 | Register validation | — | Register with a 7-char password / malformed email / blank display name | `400` each, with `fieldErrors` naming the field | P2 | R12 |
| FT-AUTH-07 | Refresh rotates and old token dies | Logged in | Refresh; then refresh again with the *old* token | First `200` with a different refresh token; second `401` | P1 | R7 |
| FT-AUTH-08 | Logout revokes | Logged in | `POST /auth/logout`; then refresh with that token | `204`, then `401` | P1 | R7 |
| FT-AUTH-09 | Logout is idempotent | — | Logout twice with the same token; logout with garbage | `204` every time | P2 | — |
| FT-AUTH-10 | Refresh token is not accepted as an access token | Logged in | `GET /experiences/mine` with `Authorization: Bearer <refreshToken>` | `401` (the `typ` claim check) | P0 | R5 |
| FT-AUTH-11 | Tampered JWT rejected | Logged in | Flip a character in the access token's signature | `401` | P0 | R5 |
| FT-AUTH-12 | Forged admin claim rejected | Non-admin user | Re-sign a token with `admin:true` using a wrong key | `401` (bad signature), and admin routes stay `403` | P0 | R5 |
| FT-AUTH-13 | Malformed `Authorization` header | — | `Bearer`, `Basic xyz`, empty, and a random string | `401` for protected routes; public routes unaffected | P2 | R12 |
| FT-AUTH-14 | Login is rate-limited per client IP | Fixed `X-Forwarded-For` | 10 failed logins, then an 11th | 11th is `429` with a JSON body | P1 | R11 |
| FT-AUTH-15 | Rate limiting is per-IP, not global | — | Exhaust the login bucket for IP A, then call from IP B | IP B is unaffected — this is the §1.3 regression | P1 | R11 |
| FT-AUTH-16 | Rate-limit window is real | Bucket exhausted | Inspect the Redis key's TTL | Key exists with a TTL of ≤ 60s (not persistent) | P2 | R11 |

### 7.2 Password reset — `FT-PWD` (`PasswordResetFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-PWD-01 | Full reset cycle | Registered user | `forgot-password` → read the raw token from the DB-side hash lookup → `reset-password` → login with the new password | `200`s throughout; new password works | P1 | R9 |
| FT-PWD-02 | Old password stops working after reset | FT-PWD-01 done | Login with the old password | `401` | P1 | R9 |
| FT-PWD-03 | Reset token is single-use | Token already used | Reuse the same token | `401`, `Invalid or expired reset token` | P0 | R5 |
| FT-PWD-04 | Expired token rejected | Token row aged past TTL via JDBC | `reset-password` | `401` | P0 | R5 |
| FT-PWD-05 | Unknown token rejected | — | `reset-password` with a random token | `401` | P1 | R5 |
| FT-PWD-06 | Token is stored hashed, never raw | `forgot-password` called | Read `password_reset_tokens.token_hash` | 64-char hex, and not equal to any token the API returned | P0 | R5 |
| FT-PWD-07 | No user enumeration | — | `forgot-password` for an unknown email | `200` with the same generic message as a known email; no token row created | P1 | R5 |
| FT-PWD-08 | Reset doesn't revoke sessions (known behaviour) | User logged in elsewhere | Reset password, then refresh with the pre-reset refresh token | Documents current behaviour — see §9 gap G4 | P2 | R7 |

### 7.3 Google Sign-In — `FT-GOOG` (`GoogleSignInFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-GOOG-01 | New Google user gets a password-less account | No matching user | `POST /auth/google` with a valid stub token | `200`; `users.password_hash IS NULL`, `google_sub` set | P1 | — |
| FT-GOOG-02 | Existing email/password account is linked, not duplicated | Registered with that email | `POST /auth/google` with the same email | `200`; still exactly one `users` row, now with `google_sub` | P1 | R5 |
| FT-GOOG-03 | Returning Google user logs in | FT-GOOG-01 done | Call again with the same subject | `200`, same user id, still one row | P1 | — |
| FT-GOOG-04 | Password login refused for a password-less account | FT-GOOG-01 done | `POST /auth/login` with any password | `401`, not a `500` (the null-hash NPE guard) | P0 | R12 |
| FT-GOOG-05 | Invalid token rejected | — | `POST /auth/google` with a junk token | `401` | P0 | R5 |

### 7.4 Authorization matrix — `FT-AUTHZ` (`AuthorizationMatrixFunctionalIT`)

Systematic sweep. Every protected route is called three ways: anonymous, as a signed-in
non-owner/non-admin, and as the legitimate actor.

| ID | Case | Expected result | Pri | Risk |
|---|---|---|---|---|
| FT-AUTHZ-01 | Anonymous hits every authenticated route (`/experiences/mine`, `POST /experiences`, `/purchases/mine`, `/payouts/mine`, submit, unpublish, history, …) | `401` on every one — never `403`, never `500` | P0 | R5 |
| FT-AUTHZ-02 | `/experiences/mine` is not swallowed by the public `/experiences/*` rule | Anonymous → `401`, not a browse response. Regression for the Phase 3 routing bug | P0 | R5 |
| FT-AUTHZ-03 | Non-admin hits every `/api/v1/admin/**` route | `403` on all of: admin queue, get-for-review, approve, reject, request-correction, payout queue, mark-paid | P0 | R5 |
| FT-AUTHZ-04 | Anonymous hits `/api/v1/admin/**` | `401` | P0 | R5 |
| FT-AUTHZ-05 | Public routes stay public | `GET /experiences`, `GET /experiences/{id}`, `GET /api/v1/health`, `/actuator/health`, `POST /payments/webhook` all reachable without a JWT | P1 | — |
| FT-AUTHZ-06 | Cross-user IDOR on write routes | User B tries update / add-round / update-round / delete-round / upload-proof / delete-proof / submit / delete on user A's experience → `403` each, and A's data is unchanged | P0 | R2 |
| FT-AUTHZ-07 | Cross-user IDOR on read routes | User B calls `GET /experiences/{a}/history` and `GET /experiences/{a}/proof/{id}` → `403` | P0 | R2 |
| FT-AUTHZ-08 | Admin override where designed | Admin can read another user's history, download their proof, unpublish and edit their experience | P1 | — |
| FT-AUTHZ-09 | Admin can't be self-granted | `bootstrap-admin` with a wrong secret → `401`, and the user stays non-admin | P0 | R5 |
| FT-AUTHZ-10 | Admin-only actuator detail | `/actuator/health` anonymous → `{"status":"UP"}` with no `components`; as admin → `db` and `redis` components present | P2 | — |
| FT-AUTHZ-11 | Actuator exposes nothing else | `/actuator/env`, `/actuator/beans`, `/actuator/loggers` → `404` even for an admin | P0 | R5 |
| FT-AUTHZ-12 | Unknown-id handling doesn't leak existence | `GET /experiences/{random}` → `404`; a *draft* owned by someone else → `404`, not `403` | P1 | R1 |

### 7.5 Submission lifecycle — `FT-SUB` (`SubmissionLifecycleFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-SUB-01 | Create draft stamps the platform price and DRAFT status | Signed in | `POST /experiences` | `201`; `status=DRAFT`, `pricePaise=9900`, `isFree=false`, `publishedAt=null` | P1 | — |
| FT-SUB-02 | Contributor can't set their own price | — | Send a `pricePaise` field in the body | Ignored; price stays the platform default | P0 | R4 |
| FT-SUB-03 | Create validation | — | Blank company / blank teaser / null outcome / month 13 / year 1999 / difficulty 6 | `400` each with `fieldErrors` | P2 | R12 |
| FT-SUB-04 | Malformed body | — | Invalid JSON; unknown enum value for `outcome` | `400 Malformed request body`, not `500` | P2 | R12 |
| FT-SUB-05 | Rounds are numbered sequentially | Draft | Add three rounds | `roundNumber` 1, 2, 3 in order | P2 | — |
| FT-SUB-06 | Round edit in place | Draft with a round | `PUT .../rounds/{id}` | Content changes; `roundNumber` and `id` unchanged | P2 | — |
| FT-SUB-07 | Round delete | Draft with rounds | `DELETE .../rounds/{id}` | `204`; round gone from the full response | P2 | — |
| FT-SUB-08 | Round belonging to another experience | Two drafts | Update/delete experience A's round via experience B's path | `404` | P1 | R2 |
| FT-SUB-09 | Submit blocked with no rounds | Draft, proof uploaded, zero rounds | `POST .../submit` | `400 Add at least one interview round…`; status stays `DRAFT` | P1 | R8 |
| FT-SUB-10 | Submit blocked with no proof | Draft, one round, no proof | `POST .../submit` | `400 Upload at least one proof document…`; status stays `DRAFT` | P0 | R8 |
| FT-SUB-11 | Submit succeeds when both present | Draft + round + proof | `POST .../submit` | `200`, `status=PENDING_REVIEW`; appears in the admin queue | P1 | R8 |
| FT-SUB-12 | Content stays editable while PENDING_REVIEW | Submitted | Edit fields, add a round | `200` — deliberate per `requireContentEditable` | P2 | — |
| FT-SUB-13 | Re-submitting a PENDING_REVIEW experience is refused | Submitted | `POST .../submit` again | `400` — `requireResubmittable` excludes `PENDING_REVIEW` | P1 | R8 |
| FT-SUB-14 | Rejected → fix → resubmit clears the old reason | Rejected with a reason | Edit, resubmit | `status=PENDING_REVIEW`, `rejectionReason=null` | P1 | R8 |
| FT-SUB-15 | Correction-requested → resubmit clears the notes | `CORRECTION_REQUESTED` | Resubmit | `status=PENDING_REVIEW`, `correctionNotes=null` | P1 | R8 |
| FT-SUB-16 | Published content is locked | Published | Edit / add round / upload proof / delete | `400` on each, with the "unpublish it first" message | P0 | R8 |
| FT-SUB-17 | Unpublish returns to DRAFT | Published | `POST .../unpublish` | `200`, `status=DRAFT`, `publishedAt=null`; disappears from browse | P1 | R10 |
| FT-SUB-18 | Unpublish only from PUBLISHED | Draft | `POST .../unpublish` | `400` | P2 | — |
| FT-SUB-19 | Purchaser keeps access after an unpublish | Purchased, then unpublished | Purchaser `GET /experiences/{id}` | `entitled=true`, full content — the regression this rule exists for | P0 | R1 |
| FT-SUB-20 | Delete a draft | Draft with rounds + proof | `DELETE /experiences/{id}` | `204`; rounds, proof rows and proof **files** all gone | P1 | R9 |
| FT-SUB-21 | Delete blocked once purchased | Purchased, then unpublished | Delete | `400` with the "has been purchased" message; nothing deleted | P0 | R4 |
| FT-SUB-22 | Delete blocked once a payout exists | Approved (payout created), then unpublished | Delete | `400` with the payout message | P0 | R4 |
| FT-SUB-23 | Delete a viewed free contribution | Free contribution, published, viewed by another user, unpublished | Delete | `204` — **regression for §1.1** (the `experience_views` FK) | P1 | R9 |
| FT-SUB-24 | Free contribution skips review | `freeContribution=true`, one round, **no proof** | Submit | `200`, `status=PUBLISHED` immediately, `pricePaise=0`, `isFree=true`, no payout row | P1 | R4 |
| FT-SUB-25 | Reference submission is admin-only | Non-admin sends `sourceUrl` | Create draft | `403` | P0 | R5 |
| FT-SUB-26 | Reference submission needs a source name | Admin sends `sourceUrl`, no `sourceName` | Create draft | `400` | P2 | — |
| FT-SUB-27 | Reference submission is free but still reviewed | Admin creates one with both fields | Submit | `PENDING_REVIEW`; `isFree=true`, `pricePaise=0` | P1 | R8 |
| FT-SUB-28 | Source fields are immutable | Reference submission exists | Edit with different `sourceUrl` / `sourceName` | Ignored — original values retained | P2 | — |
| FT-SUB-29 | Edit history records a snapshot with a diff | Draft | Change company and teaser | `GET .../history` → one entry listing exactly `Company`, `Teaser` | P2 | — |
| FT-SUB-30 | A no-op save records nothing | Draft | `PUT` the identical body | History stays empty | P2 | — |
| FT-SUB-31 | `listMine` is scoped to the caller | Two users with drafts | `GET /experiences/mine` as each | Each sees only their own | P0 | R2 |

### 7.6 Proof documents — `FT-PROOF` (`ProofDocumentFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-PROOF-01 | Upload and download round-trip | Draft | Upload a PDF, download it | `201` then `200`; bytes identical, `Content-Disposition` carries the filename | P1 | — |
| FT-PROOF-02 | Content-type allow-list | Draft | Upload `.exe` / `.zip` / `.html` / `text/plain` | `400 Unsupported file type…` on each; no row and no file created | P0 | R2 |
| FT-PROOF-03 | Allowed image types accepted | Draft | Upload PNG, JPEG, WEBP | `201` each | P2 | — |
| FT-PROOF-04 | Empty file rejected | Draft | Upload a zero-byte part | `400 Uploaded file is empty` | P2 | R12 |
| FT-PROOF-05 | Missing part | Draft | `POST .../proof` with no `file` part | `400 Missing required part 'file'` | P2 | R12 |
| FT-PROOF-06 | **Non-owner cannot download** | A's experience with proof | B downloads it | `403`, and no bytes in the body | P0 | R2 |
| FT-PROOF-07 | Anonymous cannot download | Same | No JWT | `401` | P0 | R2 |
| FT-PROOF-08 | A *purchaser* still cannot download the proof | B has bought A's experience | B downloads the proof | `403` — buying content never buys the contributor's identity documents | P0 | R2 |
| FT-PROOF-09 | Admin can download | Same | Admin downloads | `200` | P1 | — |
| FT-PROOF-10 | Proof metadata leaks nothing to a viewer | Published, purchased | Purchaser's full response | `proofDocuments` list is not usable to fetch bytes (FT-PROOF-08 holds) | P1 | R2 |
| FT-PROOF-11 | Delete removes row and file | Draft with proof | `DELETE .../proof/{id}` | `204`; row gone and file gone from disk | P1 | R9 |
| FT-PROOF-12 | Proof id from another experience | Two drafts | Download/delete A's proof via B's path | `404` | P1 | R2 |

### 7.7 Browse & discovery — `FT-BROWSE` (`BrowseFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-BROWSE-01 | Only PUBLISHED appears | One of each status | `GET /experiences` | Only published ids returned | P0 | R1 |
| FT-BROWSE-02 | Teaser only, never full content | Published with rounds | Anonymous browse | Items carry `teaser`/`roundCount` and **no** round content, prep advice or confidential note | P0 | R1 |
| FT-BROWSE-03 | No filters returns everything published | 3 published | `GET /experiences` | All 3 — exercises every `IS NULL` branch of the `CAST` query | P1 | R10 |
| FT-BROWSE-04 | Company filter, case-insensitive | Mixed-case companies | `?company=acme` | Matches `Acme` | P1 | R10 |
| FT-BROWSE-05 | Role / level / year filters | Varied | Each filter alone | Correct subset each time | P1 | R10 |
| FT-BROWSE-06 | Combined filters AND together | Varied | `?company=…&year=…` | Intersection only | P1 | R10 |
| FT-BROWSE-07 | `isFree` filter both ways | One free, one paid | `?isFree=true` / `false` | Correct partition | P1 | R10 |
| FT-BROWSE-08 | Free-text search spans company, role and teaser | Distinct keywords in each field | `?search=<keyword>` | Hits from all three fields, case-insensitively | P1 | R10 |
| FT-BROWSE-09 | Search matches nothing | — | `?search=zzzz` | Empty `items`, `totalItems=0`, `200` | P2 | — |
| FT-BROWSE-10 | Pagination arithmetic | 5 published | `?page=0&size=2`, then `page=1`, `page=2` | 2 / 2 / 1 items; `totalItems=5`, `totalPages=3`; no id appears twice | P1 | R10 |
| FT-BROWSE-11 | Negative page/size clamped | — | `?page=-1&size=0` | `200` (not `500`) — **regression for §1.7** | P1 | R12 |
| FT-BROWSE-12 | Oversized page clamped | — | `?size=5000` | `pageSize` ≤ 100 (`app.pagination.max-page-size`) | P2 | R12 |
| FT-BROWSE-13 | Sort modes | Varied price and publish times | `sort=newest` / `priceLow` / `priceHigh` / `mostViewed` | Correct ordering each time | P2 | R10 |
| FT-BROWSE-14 | Unknown sort falls back silently | — | `sort=bogus` | `200`, newest-first | P2 | R12 |
| FT-BROWSE-15 | `unlocked` reflects the caller | B purchased one of two | Browse as B | Purchased card `unlocked=true`, other `false`; anonymous sees `false` on both | P0 | R1 |
| FT-BROWSE-16 | Free experiences read as unlocked | Free published | Browse anonymously | `unlocked=true` | P2 | — |
| FT-BROWSE-17 | `roundCount` is right, content isn't exposed | 3 rounds | Browse | `roundCount=3`, still no round bodies | P1 | R1 |

### 7.8 Detail view & the paywall — `FT-VIEW` (`ExperienceViewFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-VIEW-01 | Anonymous sees the teaser | Published, paid | `GET /experiences/{id}` | `entitled=false`, `teaser` present, `full` **absent from the JSON**, not just null | P0 | R1 |
| FT-VIEW-02 | Signed-in non-purchaser sees the teaser | Same | As user B | `entitled=false` | P0 | R1 |
| FT-VIEW-03 | Owner sees everything | Same | As owner | `entitled=true`, rounds present, `confidentialNote` present | P1 | — |
| FT-VIEW-04 | Admin sees everything | Same | As admin | `entitled=true`, `confidentialNote` present | P1 | — |
| FT-VIEW-05 | Purchaser sees content but **not** the confidential note | B entitled | As B | `entitled=true`, rounds present, `confidentialNote=null` | P0 | R6 |
| FT-VIEW-06 | Free published is open to everyone including guests | Free published | Anonymous | `entitled=true`, full content, `confidentialNote=null` | P1 | R6 |
| FT-VIEW-07 | Unpublished is invisible to strangers | Draft/pending | As user B and anonymous | `404` (not `403` — no existence leak) | P0 | R1 |
| FT-VIEW-08 | Unknown id | — | Random UUID | `404` | P2 | R12 |
| FT-VIEW-09 | Non-UUID id | — | `GET /experiences/not-a-uuid` | `400 Invalid value for 'id'`, not `500` | P2 | R12 |
| FT-VIEW-10 | View counted once per signed-in viewer | Published | B loads it 3×, C loads it once | `viewCount=2`; `experience_views` has 2 rows | P2 | — |
| FT-VIEW-11 | Guests are never counted | Published | Anonymous loads it twice | `viewCount` unchanged | P2 | — |
| FT-VIEW-12 | Owner and admin views count too | Published | Owner loads it | `viewCount` increments — documents intended behaviour | P2 | — |
| FT-VIEW-13 | Unpublished views aren't counted | Draft | Owner loads it | `viewCount` stays 0 | P2 | — |
| FT-VIEW-14 | The response reflects the caller's own view | Published | First load by B | `viewCount` in that same response is already 1 (the re-read after increment) | P2 | — |
| FT-VIEW-15 | **Concurrent first views by different users don't 409** | Published | 8 distinct users `GET` simultaneously | All `200`; final `viewCount=8` — **regression for §1.2** | P1 | R12 |

### 7.9 Admin review — `FT-REVIEW` (`AdminReviewFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-REVIEW-01 | Queue holds only PENDING_REVIEW, oldest first | One of each status | `GET /admin/experiences` | Only pending, ascending by creation | P1 | R8 |
| FT-REVIEW-02 | Queue carries the full substance | Pending with 2 rounds + proof | Same | Each item has populated `rounds`, `proofDocuments`, `confidentialNote` — the data §1.5 says the UI must render | P1 | R8 |
| FT-REVIEW-03 | Approve publishes, logs and creates the payout | Pending | `POST .../approve` | `200 PUBLISHED`, `publishedAt` set; one `review_logs` row `APPROVED`; one `payouts` row `PENDING` at 50000 paise | P0 | R4 |
| FT-REVIEW-04 | Approve is not repeatable | Approved | Approve again | `400`; still exactly one payout row | P0 | R4 |
| FT-REVIEW-05 | Approve only from PENDING_REVIEW | Draft | Approve | `400` | P1 | R8 |
| FT-REVIEW-06 | Approving a free reference creates no payout | Reference submission pending | Approve | `PUBLISHED`, and **zero** payout rows | P0 | R4 |
| FT-REVIEW-07 | Reject records the reason | Pending | `POST .../reject` with a reason | `200 REJECTED`, `rejectionReason` set, `review_logs` row `REJECTED` | P1 | R8 |
| FT-REVIEW-08 | Reject requires a reason | Pending | Blank/missing reason | `400` with `fieldErrors.reason` | P2 | R12 |
| FT-REVIEW-09 | Reject creates no payout | Pending | Reject | Zero payout rows | P0 | R4 |
| FT-REVIEW-10 | Request-correction records notes | Pending | `POST .../request-correction` | `200 CORRECTION_REQUESTED`, `correctionNotes` set, log row written | P1 | R8 |
| FT-REVIEW-11 | Request-correction requires notes | Pending | Blank notes | `400` | P2 | R12 |
| FT-REVIEW-12 | Admin can edit a submission during review | Pending | Admin `PUT /experiences/{id}` | `200`; change applied and it shows in the contributor's edit history | P1 | — |
| FT-REVIEW-13 | Approve → unpublish → resubmit → approve doesn't double-pay | Full cycle | Run it | Still exactly **one** payout row (unique constraint on `experience_id`) — if this fails it is a real money bug, see §9 G5 | P0 | R4 |
| FT-REVIEW-14 | Review log is an audit trail | Reject then approve | Read `review_logs` | Both rows present, with the acting admin's id | P1 | R8 |
| FT-REVIEW-15 | `getForReview` on an unknown id | — | Random UUID | `404` | P2 | R12 |

### 7.10 Purchases & entitlement — `FT-PAY` (`PurchaseFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-PAY-01 | Order refused for an unpublished experience | Draft | `POST /experiences/{id}/purchase` | `400 This experience isn't published yet` | P0 | R3 |
| FT-PAY-02 | Order refused for a free experience | Free published | Same | `400 This experience is free…` | P1 | R3 |
| FT-PAY-03 | Order refused when already entitled | Entitled | Same | `400 You already have access…` | P0 | R4 |
| FT-PAY-04 | Order refused for an unknown experience | — | Random UUID | `404` | P2 | R12 |
| FT-PAY-05 | Order refused when payments are misconfigured | Blank key-id (FT profile) | Published, not entitled | `400 …payments aren't configured correctly` — proves the guard order | P1 | R12 |
| FT-PAY-06 | Confirm with a valid signature grants entitlement | `CREATED` purchase exists | `POST /purchases/confirm` | `200 PAID`; exactly one `entitlements` row | P0 | R3 |
| FT-PAY-07 | **Confirm with a bad signature grants nothing** | `CREATED` purchase | Wrong signature | `400 Payment could not be verified`; purchase `FAILED`; **zero** entitlements | P0 | R3 |
| FT-PAY-08 | Signature is bound to this order and payment | Two purchases | Confirm order A with order B's signature | `400`; no entitlement | P0 | R3 |
| FT-PAY-09 | Confirm someone else's order | A's purchase | B confirms it | `404` (not `403` — no existence leak); no entitlement for either | P0 | R3 |
| FT-PAY-10 | Confirm an unknown order | — | Random order id | `404` | P2 | R12 |
| FT-PAY-11 | Confirm is idempotent | Already `PAID` | Confirm again, even with a junk signature | `200 PAID`; still exactly one entitlement — documents the deliberate short-circuit | P0 | R4 |
| FT-PAY-12 | Entitlement unlocks the full write-up | Confirmed | `GET /experiences/{id}` as the buyer | `entitled=true`, rounds present, `confidentialNote=null` | P0 | R1, R6 |
| FT-PAY-13 | Entitlement is per user | B entitled | C reads it | `entitled=false` | P0 | R1 |
| FT-PAY-14 | Entitlement is per experience | B entitled to X | B reads Y | `entitled=false` | P0 | R1 |
| FT-PAY-15 | Unique constraint blocks a second entitlement | Entitled | Insert a duplicate directly via JDBC | Constraint violation — the DB, not the app, is the last line of defence | P0 | R4 |
| FT-PAY-16 | `unlockCount` tracks real unlocks | 2 buyers | Owner's view | `unlockCount=2` | P2 | — |
| FT-PAY-17 | `/purchases/mine` is caller-scoped | Two buyers | Each calls it | Each sees only their own; `company`/`roleTitle` populated | P0 | R2 |
| FT-PAY-18 | Confirm validation | — | Missing/blank signature fields | `400` with `fieldErrors` | P2 | R12 |
| FT-PAY-19 | Concurrent confirm of the same order | `CREATED` purchase | Two simultaneous confirms | Both resolve without a `500`; exactly one entitlement | P0 | R4 |

### 7.11 Razorpay webhook — `FT-HOOK` (`WebhookFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-HOOK-01 | Reachable without a JWT | — | `POST /payments/webhook` | Not `401` for missing auth — reaches signature checking | P1 | — |
| FT-HOOK-02 | Missing signature header rejected | — | No `X-Razorpay-Signature` | `401`; nothing granted | P0 | R3 |
| FT-HOOK-03 | Wrong signature rejected | — | Garbage signature | `401`; nothing granted | P0 | R3 |
| FT-HOOK-04 | Signature over a *different* body rejected | — | Valid signature for body A, sent with body B | `401` — proves the HMAC covers the payload | P0 | R3 |
| FT-HOOK-05 | Valid `payment.captured` grants entitlement | `CREATED` purchase | Correctly signed event | `200`; purchase `PAID`, one entitlement | P0 | R3 |
| FT-HOOK-06 | Webhook then client-confirm is idempotent | Webhook already granted | Client confirms the same order | `200 PAID`; still one entitlement | P0 | R4 |
| FT-HOOK-07 | Client-confirm then webhook is idempotent | Confirm already granted | Webhook fires | `200`; still one entitlement | P0 | R4 |
| FT-HOOK-08 | Other event types ignored safely | — | Correctly signed `payment.failed` | `200`; nothing changes | P1 | — |
| FT-HOOK-09 | Unknown order id ignored safely | — | Signed event for an order we don't have | `200`, logged, no crash | P1 | R12 |
| FT-HOOK-10 | Malformed JSON with a valid signature | — | Signed non-JSON body | Handled without a stack trace to the client (`4xx`/`5xx` contract asserted) | P2 | R12 |
| FT-HOOK-11 | Raw-body reading works | — | Any valid call | Doesn't `400` on body binding — regression for the `@RequestBody String` trap | P1 | R12 |

### 7.12 Payouts — `FT-POUT` (`PayoutFunctionalIT`)

| ID | Case | Precondition | Steps | Expected result | Pri | Risk |
|---|---|---|---|---|---|---|
| FT-POUT-01 | Approval puts a payout in the admin queue | Approved | `GET /admin/payouts` | One `PENDING` row with contributor email, display name, company, role, 50000 paise | P0 | R4 |
| FT-POUT-02 | Mark paid transitions and records | Pending payout | `POST .../mark-paid` with a reference | `200 PAID`; `payout_reference` and `paid_by_admin_id` persisted; `paidAt` set | P0 | R4 |
| FT-POUT-03 | Reference is optional | Pending payout | Mark paid with no body | `200 PAID`, `payoutReference=null` | P1 | — |
| FT-POUT-04 | **Double payment refused** | Already `PAID` | Mark paid again | `400 already marked paid` | P0 | R4 |
| FT-POUT-05 | Paid rows leave the queue | Paid | `GET /admin/payouts` | Not listed | P1 | — |
| FT-POUT-06 | Unknown payout id | — | Random UUID | `404` | P2 | R12 |
| FT-POUT-07 | Reference length validation | — | 300-char reference | `400` (`@Size(max=255)`) | P2 | R12 |
| FT-POUT-08 | `/payouts/mine` is caller-scoped | Two contributors, one payout each | Each calls it | Each sees only their own | P0 | R2 |
| FT-POUT-09 | `/payouts/mine` hides other contributors' identity fields | Own payout | Call it | `contributorEmail` and `contributorDisplayName` are null | P1 | R2 |
| FT-POUT-10 | Contributor sees the paid state | Marked paid | Contributor calls `/payouts/mine` | `PAID` with the reference and `paidAt` | P1 | — |
| FT-POUT-11 | No payout for a free contribution | Free contribution published | Admin queue | Empty | P0 | R4 |
| FT-POUT-12 | Non-admin can't reach payout admin routes | Signed-in non-admin | Both admin payout routes | `403` | P0 | R5 |

### 7.13 Platform, schema and error contract — `FT-OPS` (`PlatformFunctionalIT`)

| ID | Case | Expected result | Pri | Risk |
|---|---|---|---|---|
| FT-OPS-01 | Flyway applied every migration cleanly | `flyway_schema_history` has V1–V10, all `success=true`, no failed rows | P1 | — |
| FT-OPS-02 | JPA schema validation passes | Context starts with `ddl-auto=validate` — every entity matches the migrated schema. Implicit in every FT, asserted explicitly here | P0 | — |
| FT-OPS-03 | `GET /api/v1/health` | `200`, `UP` | P2 | — |
| FT-OPS-04 | Actuator liveness/readiness are anonymous | `200` on both | P2 | — |
| FT-OPS-05 | Security response headers present | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` on API responses | P1 | R5 |
| FT-OPS-06 | CORS preflight succeeds for an allowed origin on an authenticated route | `OPTIONS /api/v1/experiences/mine` with `Origin: http://localhost:5173` → `200`/`204` with `Access-Control-Allow-Origin` — regression for the Phase 3 CORS bug | P1 | R12 |
| FT-OPS-07 | CORS refuses a foreign origin | `Origin: https://evil.example` → no permissive `Access-Control-Allow-Origin` | P0 | R5 |
| FT-OPS-08 | Error body shape is consistent | Every 4xx carries `timestamp`, `status`, `error`, `message`; validation failures add `fieldErrors` | P2 | R12 |
| FT-OPS-09 | Unsupported method | `DELETE /api/v1/health` → `405`, not `500` | P2 | R12 |
| FT-OPS-10 | Unknown route | `GET /api/v1/nope` → `401` or `404`, never a stack trace | P2 | R12 |
| FT-OPS-11 | Oversized upload | 11 MB proof file → `413`, not `500` | P2 | R12 |
| FT-OPS-12 | Internals never leak in an error body | No error response contains `Exception`, `org.springframework`, or a SQL fragment | P0 | R5 |

---

## 8. Traceability

| Suite | Cases | File |
|---|---|---|
| FT-AUTH | 16 | `functional/AuthFunctionalIT.java` |
| FT-PWD | 8 | `functional/PasswordResetFunctionalIT.java` |
| FT-GOOG | 5 | `functional/GoogleSignInFunctionalIT.java` |
| FT-AUTHZ | 12 | `functional/AuthorizationMatrixFunctionalIT.java` |
| FT-SUB | 31 | `functional/SubmissionLifecycleFunctionalIT.java` |
| FT-PROOF | 12 | `functional/ProofDocumentFunctionalIT.java` |
| FT-BROWSE | 17 | `functional/BrowseFunctionalIT.java` |
| FT-VIEW | 15 | `functional/ExperienceViewFunctionalIT.java` |
| FT-REVIEW | 15 | `functional/AdminReviewFunctionalIT.java` |
| FT-PAY | 19 | `functional/PurchaseFunctionalIT.java` |
| FT-HOOK | 11 | `functional/WebhookFunctionalIT.java` |
| FT-POUT | 12 | `functional/PayoutFunctionalIT.java` |
| FT-OPS | 12 | `functional/PlatformFunctionalIT.java` |
| | **185** | |

Every test method is annotated with its case ID in a `@DisplayName`, so a failure report names
the case directly. Harness lives in `functional/support/`.

---

## 9. Known gaps and open questions

Numbered so they can be tracked. These are *not* covered by this suite, by choice or by
constraint.

- **G1 — No browser E2E.** The §1.4/§1.5 findings (raw enum round types shown to paying viewers;
  admin approving content the UI never renders) are **UI** bugs. FT-REVIEW-02 and FT-PAY-12 prove
  the *API* returns the right data, which is as far as this layer can reach. Closing those
  findings needs a Playwright suite or manual verification.
- **G2 — Razorpay order creation is never called.** See §6.1.
- **G3 — Real Google token verification is stubbed.** See §6.4.
- **G4 — Password reset doesn't revoke existing sessions.** FT-PWD-08 pins the current behaviour
  rather than asserting a fix. Worth a product decision: after a reset (which is what a user does
  when they think they've been compromised), outstanding refresh tokens arguably should die.
- **G5 — Republish-after-unpublish and the payout ledger.** FT-REVIEW-13 exercises
  approve → unpublish → resubmit → approve. `payouts.experience_id` is `UNIQUE`, so the second
  approval's insert should fail on the constraint and surface as a `409`. If it does, the
  contributor can't be paid twice (good) but the admin gets an opaque error on a legitimate
  re-approval (bad). This test is the one most likely to reveal a real defect; treat a failure
  here as a finding, not a broken test.
- **G6 — No concurrency testing beyond FT-VIEW-15 and FT-PAY-19.** Optimistic-lock behaviour
  under real contention on edits is untested.
- **G7 — Rate limiting is per-IP only.** A distributed credential-stuffing attempt is out of
  scope for the implementation, so also for the tests.
- **G8 — No test asserts that proof files are encrypted at rest**, because they aren't. Open
  item #3 in `04-handoff.md`.

---

## 10. Entry and exit criteria

**Entry** — the API compiles, Docker is running, `./mvnw test` is green.

**Exit, per change**

| Gate | Criterion |
|---|---|
| Any commit | `./mvnw test` green |
| Any PR | `./mvnw verify` green — all P0 and P1 cases pass |
| Deploy | 100% of P0 pass. No P0 may be `@Disabled` without a linked, accepted risk note |
| Release | P2 failures triaged; each is either fixed or recorded here as a known gap |

**Defect triage** — a failing FT is a defect until proven otherwise. Before changing a test to
make it pass, confirm the *specification* in this document is wrong; if the code is wrong, fix
the code. Findings that turn out to be genuine bugs get filed against
`07-application-review.md`'s numbering scheme.
