# Handoff — Continue Here

Read this first when you resume.

## The idea (locked)

**Know Your Interview** — a verified marketplace for structured interview experiences.
- Contributors submit their real interview experience → an **admin verifies** it → on publish they get a **one-time flat fee**.
- Viewers **pay per experience** to unlock and read the full structured content.
- **Platform sets the price.** Verification = **proof upload + admin review**.
- **Launch market: India** → payments via **Razorpay** (RazorpayX for payouts), amounts in INR/paise.

## Decisions locked

| Decision | Choice |
|---|---|
| Repo style | Monorepo (`api` / `web` / `mobile` / `shared`) |
| Backend | Java 21 · Spring Boot 4.1 · Maven |
| Web | React 19 + Vite + TypeScript |
| Mobile | Expo / React Native |
| Database | PostgreSQL (+ Redis) |
| API style | REST (`/api/v1`) |
| Payments | Razorpay (India) |
| Cloud (later) | AWS |
| Payout model | One-time flat fee on publish |
| Viewer pricing | Pay-per-experience |
| Verification | Proof upload + admin review |

## What's built (Phase 1 — walking skeleton) — generated 2026-07-20

- Monorepo structure (`api/`, `web/`, `mobile/`, `shared/`, `docs/`) + `docker-compose.yml` (Postgres 17, Redis 7).
- Backend build tool: **Maven** (switched from Gradle on 2026-07-20 at your request) — `api/pom.xml`. The old `build.gradle.kts`/`settings.gradle.kts` are stub files left behind since they couldn't be auto-deleted; safe to remove.
- Spring Boot API: `KnowYourInterviewApplication`, `GET /api/v1/health`, and a passing `HealthControllerTest`.
- **Flyway `V1__init.sql`** — full initial DB schema: users, experiences, experience_rounds, proof_documents, purchases, entitlements, payout_accounts, payouts, review_logs.
- `shared/types.ts` — TypeScript API contract (health, auth, experiences, purchases, entitlements, payouts).
- `.github/workflows/backend-ci.yml` — builds/tests the API against a Postgres service container on push/PR.
- `web/` — scaffolded with Vite (React 19.2 + TypeScript), wired to the API: `web/src/lib/api.ts` calls `GET /api/v1/health`, `web/src/App.tsx` displays it. Confirmed working end-to-end (browser → CORS-enabled API → Postgres/Redis via docker-compose) on 2026-07-20.
- `mobile/` = README placeholder only, intentionally deferred — focus is web-first for now.
- ~~`api/.../config/WebConfig.java` — dev CORS config~~ — superseded in Phase 3, see the CORS note below. Safe to delete that file.

**Status: backend confirmed running (`/api/v1/health` → `UP`, Flyway applied); web app confirmed running and talking to the API.** Repo pushed to GitHub. Mobile not started (deferred).

## What's built (Phase 2 — authentication) — generated 2026-07-20

- **Auth model:** every registered user is `ROLE_USER`; `is_admin` (a boolean on `users`, already in the schema) additionally grants `ROLE_ADMIN`. There's no separate "contributor" role — per `02-phase0-design.md`, any user can submit an experience; contributor/viewer are activities, not privilege levels. This is a simplification of the roadmap's literal "USER/CONTRIBUTOR/ADMIN" wording, chosen to match the actual DB schema and Phase 0 design.
- **JWT, stateless:** short-lived access tokens (15 min, `app.jwt.access-token-ttl-minutes`) sent as `Authorization: Bearer <token>`; longer-lived refresh tokens (30 days) tracked in **Redis** by `jti` so refresh/logout can actually revoke them. Refresh rotates the token (old one is single-use). `api/.../security/{JwtService,JwtAuthenticationFilter,SecurityConfig,AuthenticatedUser}.java`.
- **Endpoints** (`api/.../auth/{AuthController,AuthService}.java`): `POST /api/v1/auth/{register,login,refresh,logout,forgot-password,reset-password}`. All public; everything else now requires a valid access token (`SecurityConfig` — only `/api/v1/health` and `/api/v1/auth/**` are `permitAll`).
- **Password reset is stubbed**, per your call: `forgot-password` generates and stores a hashed token (`V2__password_reset_tokens.sql`) and **logs the raw reset link to the console** instead of emailing it (no email provider set up yet — see `01-build-roadmap.md` accounts list for Postmark/SendGrid). Swap the `log.info(...)` in `AuthService.forgotPassword` for a real send once that's wired up.
- **Web:** `AuthContext` (`web/src/context/AuthContext.tsx`) + login/register forms (`web/src/components/AuthForms.tsx`), tokens in `localStorage` (MVP simplification — readable by any JS on the page; revisit with an httpOnly refresh cookie before this holds real user data at scale — noted in `web/src/lib/authStorage.ts`).
- **Tests:** `JwtServiceTest` (pure unit, no Spring context) and `AuthControllerTest` (`@WebMvcTest` + mocked `AuthService`) — no DB/Redis needed to run these. `HealthControllerTest` was converted from `@SpringBootTest` to the same `@WebMvcTest` slice pattern for the same reason (a full context now needs a live DB + Redis via `AuthService`'s dependencies). **No integration test exercises a real register→login→refresh flow against real Postgres/Redis yet** — worth adding (via Testcontainers) before Phase 5 hardening, and definitely before auth handles real user data.
- **No auto refresh-on-401 in the web client yet** — there are no protected data endpoints to need it against yet (Phase 3 will have some); `api.ts`/`AuthContext` are structured so that's a small addition when it's needed.

### Bootstrapping your first admin

There's no promote-to-admin endpoint (by design — admin status shouldn't be self-service). After you've registered your first account, flip it manually:

```sql
UPDATE users SET is_admin = true WHERE email = 'you@example.com';
```

## What's built (Phase 3 — core domain) — generated 2026-07-20

- **Domain:** `Experience`, `ExperienceRound`, `ProofDocument`, `ReviewLog`, `Payout` JPA entities (`api/.../experience/`), mapped onto the existing `V1__init.sql` schema — no new migration needed here. Rounds/proof docs are queried by `experienceId` via their own repositories rather than JPA `@OneToMany` associations on `Experience`, to keep things simple and avoid lazy-loading surprises.
- **Pricing is platform-set, not contributor-set** (per `02-phase0-design.md`): `app.pricing.default-price-paise` (viewer unlock price, ₹99 default) is stamped onto every experience at creation; `app.pricing.contributor-payout-paise` (₹500 flat fee default) is a **separate** config used only when an admin approves. Both are in `application.yml` — change them there, not per-experience.
- **Contributor flow** (`ExperienceController`, all under `/api/v1/experiences`, auth required except browse): create/edit draft → add/remove rounds → upload proof → submit for review. Submit is blocked (400) unless there's ≥1 round and ≥1 proof document. Editing/rounds/proof are only allowed while `status = DRAFT`.
- **Proof storage:** local disk under `api/uploads/proof/` (gitignored, path configurable via `app.storage.proof-dir` / `PROOF_STORAGE_DIR`), behind a `ProofStorageService` interface (`LocalProofStorageService` is the only implementation) — swap in an S3-backed one for Phase 6 without touching callers. Files are served back through `GET /api/v1/experiences/{id}/proof/{proofId}`, gated to the owning contributor or an admin.
- **Browse/public** (`GET /api/v1/experiences`, `GET /api/v1/experiences/{id}` — both `permitAll` in `SecurityConfig`): teaser-only, filterable by company/role/level/year, paginated (`PagedResponse<T>`). Single-experience `GET` returns the shared `ExperienceView` union — `{entitled:false, teaser}` for the public, `{entitled:true, full}` for the owning contributor or an admin. **"entitled" here is broader than an actual purchase** (there's no Entitlement table data yet — Phase 4 owns that); it currently just means "allowed to see full content for review/ownership reasons." Worth a comment or rename once real paid entitlements exist, so the two concepts don't get confused.
- **Admin review** (`AdminReviewController`, all under `/api/v1/admin/**`, `hasRole("ADMIN")` in `SecurityConfig`): queue of `PENDING_REVIEW` experiences, approve (→ `PUBLISHED`, writes a `ReviewLog`, and creates a `Payout` row at `PENDING` — **ledger only, no actual RazorpayX transfer yet**, that's Phase 4) or reject (with a required reason, also logged).
- **Web:** view-switcher nav (still no router — `browse` / `my submissions` / `admin review`, admin tab only shown to admins). `SubmissionWorkspace.tsx` covers create-draft → add rounds → upload proof → submit in one screen; `BrowseExperiences.tsx` and `AdminReviewQueue.tsx` are simpler list views.
- **Tests:** `ExperienceControllerTest` and `AdminReviewControllerTest`, same `@WebMvcTest` + mocked-service pattern as auth, plus a regression test for a routing bug caught during review (see below).
- **A CORS bug caught after you hit it in the browser:** `WebConfig`'s `addCorsMappings` (from Phase 1) only configures CORS at the Spring MVC layer, but Spring Security's filter chain sits in *front* of MVC and intercepts preflight `OPTIONS` requests first. `/health` and `/auth/**` never showed this because those calls don't send an `Authorization` header, so browsers don't preflight them — but any authenticated call (all of `/experiences/**` except plain browse) does. Fixed by moving CORS into `SecurityConfig` itself: a `CorsConfigurationSource` bean wired via `.cors(cors -> cors.configurationSource(...))` on the `SecurityFilterChain`, so Security's own `CorsFilter` handles preflight before the authorization rules run. `WebConfig.java` is now a dead stub — delete it whenever.
- **A routing bug caught before it shipped:** `/api/v1/experiences/mine` is one path segment, same shape as `/api/v1/experiences/{id}` — it would have silently matched the public browse `permitAll` pattern and let anonymous requests reach a controller method that assumes an authenticated user (NPE → 500 instead of 401). Fixed by listing `/mine` as its own `authenticated()` rule *before* the broader pattern in `SecurityConfig` (rules are first-match-wins). Worth remembering if more `/experiences/<word>`-shaped routes get added later — they'll need the same treatment.

## What's built (Phase 4 — Razorpay payments, viewer side) — generated 2026-07-21

- **Scope note:** this section covers the *viewer-pays-to-unlock* side. Contributor payouts (the other half of Phase 4) are covered further down — see "Phase 4 — Contributor payouts (manual batch)".
- **Status:** you confirmed the flow works end-to-end through opening Razorpay Checkout, but weren't able to complete an actual test payment — most likely a Razorpay Test Mode account setup thing on their side rather than a bug here, so treating the code as done for now. Worth another look if it keeps failing once your account's fully activated.
- **Domain:** `Purchase` and `Entitlement` JPA entities (`api/.../payment/`), mapped onto the existing `purchases`/`entitlements` tables from `V1__init.sql` — no new migration needed. A `Purchase` tracks one checkout attempt (`CREATED` → `PAID`/`FAILED`); a paid `Purchase` grants exactly one `Entitlement` row (checked for existence before inserting, so retries/duplicate webhook calls can't create two).
- **Order creation** (`POST /api/v1/experiences/{id}/purchase`, auth required): rejects if the experience isn't `PUBLISHED` or the caller already holds an entitlement, otherwise opens a Razorpay Order via the SDK and returns `{razorpayOrderId, amountPaise, currency, razorpayKeyId}` — everything the browser needs to open Razorpay's Checkout widget. Deliberately does **not** set a `payment_capture` field on the order — that's an account-level Dashboard setting in current Razorpay API versions, not a documented per-order parameter.
- **Confirmation is dual-path, per your call ("client-confirm + webhook backup")** — both funnel into the same idempotent `grantEntitlement` helper in `PurchaseService`, so it doesn't matter which fires first or if both do:
  - **Primary — client confirm** (`POST /api/v1/purchases/confirm`): the browser calls this immediately after Razorpay Checkout's success handler fires, forwarding `razorpay_order_id`/`razorpay_payment_id`/`razorpay_signature`. Verified server-side via `Utils.verifyPaymentSignature` before granting anything.
  - **Backup — webhook** (`POST /api/v1/payments/webhook`, publicly reachable, **no JWT** — Razorpay's servers call this, not a logged-in browser): handles `payment.captured` events, in case the user's tab closed before the client-confirm call went out. Authenticity comes entirely from the `X-Razorpay-Signature` header, verified via `Utils.verifyWebhookSignature` against `RAZORPAY_WEBHOOK_SECRET` — `SecurityConfig` has a `permitAll` rule for this one path specifically because there's no other way to authenticate it.
  - `WebhookController` reads the raw request body via `HttpServletRequest.getInputStream()` rather than `@RequestBody String` — with Jackson on the classpath, Spring would otherwise try to JSON-deserialize the body straight into a `String` and 400 before signature verification ever got a chance to run. Worth remembering for any future raw-body endpoint.
- **Real entitlement check** wired into `ExperienceService.getPublicView` — this resolves the gap flagged at the end of Phase 3 (`getPublicView`'s "entitled" flag conflating ownership/review access with actual payment). Full content is now returned for: the owning contributor, an admin, **or** a viewer with a real paid `Entitlement` row. Everyone else still gets the teaser.
- **Config:** `com.razorpay:razorpay-java:1.4.9` in `pom.xml`; `app.razorpay.{key-id,key-secret,webhook-secret}` in `application.yml`, all sourced from env vars with **no local-dev default** (`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`) — Test Mode keys from the Razorpay dashboard are safe to use locally since they don't move real money. If those env vars are unset, `PurchaseService` throws a clear error only when someone actually tries to check out — it doesn't block app startup, so the rest of the app works fine without Razorpay configured.
- **Web:** `web/src/lib/razorpay.ts` lazy-loads `checkout.js` on first use (pages that never touch payments don't pay for it). `ExperienceDetail.tsx` is a new click-through view (wired from a "View" button on both `BrowseExperiences` and the new `MyLibrary` tab): shows the teaser + an "Unlock ₹X" button when not entitled, opens Razorpay's Checkout modal, calls `/purchases/confirm` on success, then reloads to show the full write-up (rounds, prep advice, timeline, etc.) once entitled. `MyLibrary.tsx` lists the caller's `PAID` purchases. New "My library" nav tab in `App.tsx`.
- **Tests:** `PurchaseControllerTest` and `WebhookControllerTest`, same `@WebMvcTest` + `@Import({SecurityConfig, JwtService})` + mocked-service slice pattern as the rest of the suite — cover auth-required-on-purchase/confirm/mine, the webhook's permitAll reachability, missing/invalid signature rejection, and correct routing of `payment.captured` into `handlePaymentCaptured`. **Written but not run in this environment** — the sandbox this was built in didn't have JDK 21/Maven installed and had no permission or network access to install them. Run `mvn test` locally to confirm before treating Phase 4 as fully verified (the web side did get a clean `tsc --noEmit` typecheck).

### Known gaps worth knowing about (viewer-side payments)

- **Webhook is untested against real Razorpay servers.** Razorpay can't reach `localhost` — testing the backup path for real needs either an ngrok tunnel pointed at your local backend (set that URL + a webhook secret in the Razorpay dashboard's Webhooks section) or a deployed backend. Until then, the client-confirm path is the one actually exercised locally.
- No refund flow.
- No integration test exercises a real purchase end-to-end against real Postgres/Redis/Razorpay — worth adding via Testcontainers before Phase 5 hardening, same gap noted for auth in Phase 2.

## What's built (Phase 4 — Contributor payouts, manual batch) — generated 2026-07-21

- **Why manual, not a live RazorpayX transfer:** RazorpayX (Razorpay's payouts product) needs a separate **Current Account with its own business KYC approval** — a materially bigger lift than the standard Razorpay Payments Test Mode used for viewer checkout, which you can start using the moment you sign up. You confirmed you don't have that yet, so per your call, this is a **manual-batch** flow for now: an admin sends the contributor's flat fee themselves (bank transfer/UPI, outside the app) and then records it as paid. Also worth knowing: `razorpay-java` 1.4.9 (the SDK already in `pom.xml`) doesn't even ship Contact/FundAccount/Payout clients for RazorpayX — those APIs would need raw HTTP calls if/when this gets built for real.
- **Schema:** `V3__payout_manual_tracking.sql` adds `paid_by_admin_id` and `payout_reference` (free-text, e.g. a UPI transaction ID, for the admin's own reconciliation — not validated or looked up) to the existing `payouts` table. The `PENDING`/`PROCESSING`/`PAID`/`FAILED` status model from Phase 1's schema was left alone, on purpose — swapping in a real RazorpayX call later (adding a `razorpayxPayoutId`-setting path alongside `markPaid`) won't need another migration.
- **`Payout.markPaid(adminId, reference)`** — the only state transition added; there's no un-mark or edit-after-paid path (by design, to avoid double-payment confusion — `PayoutService.markPaid` throws if the row is already `PAID`).
- **Endpoints:** `GET /api/v1/admin/payouts` (`hasRole("ADMIN")`, no `SecurityConfig` change needed — already covered by the existing `/api/v1/admin/**` rule) lists everything `PENDING`/`PROCESSING`, enriched with contributor email/name and experience company/role for the admin to actually act on. `POST /api/v1/admin/payouts/{id}/mark-paid` takes an optional `{reference}` body. `GET /api/v1/payouts/mine` (any authenticated user) lets a contributor see their own payout history — falls under the existing catch-all `anyRequest().authenticated()` rule, also no `SecurityConfig` change needed.
- **Web:** new "Admin payouts" tab (`AdminPayouts.tsx`, admin-only) — one card per owed payout with a reference input and "Mark paid" button. New "My payouts" tab (`MyPayouts.tsx`, all authenticated users) — read-only history.
- **Tests:** `AdminPayoutControllerTest` (auth/role gating on both admin routes, queue response shape, mark-paid with and without a reference body) and `PayoutControllerTest` (auth-required + own-payouts-only on `/mine`) — same `@WebMvcTest` slice pattern as the rest of the suite. **Not run in this environment**, same JDK 21/Maven sandbox limitation as the viewer-payment tests above — run `mvn test` locally.

### Known gaps worth knowing about (contributor payouts)

- **This is genuinely manual** — nothing here sends money. If you forget to actually wire a contributor's fee before hitting "Mark paid," there's no reconciliation check catching that; the reference field is free text, not verified against anything.
- **RazorpayX integration itself is still just a plan, not code.** Whenever you do get RazorpayX approved, this needs real implementation work (raw HTTP calls per above), not just flipping a config flag.
- No email/notification to the contributor when their payout is marked paid, or to the admin when one becomes owed — they'd need to check "My payouts" / "Admin payouts" themselves.

## What's built (Phase 5 — hardening & observability) — generated 2026-07-21

- **Testcontainers integration tests** — the gap flagged in every phase so far ("no test exercises this against real Postgres/Redis"). `api/src/test/java/.../support/ContainerConfig.java` is a shared `@TestConfiguration` (real Postgres 17 + Redis 7 containers, wired via Spring Boot's `@ServiceConnection` — no manual `@DynamicPropertySource` needed for datasource/redis properties). Two new suites, both named `*IT.java` on purpose:
  - `AuthFlowIT` — register → login → refresh (rotation + reuse-after-rotation rejected) → logout (revocation), plus the new rate-limiting and actuator-health checks below.
  - `PurchaseFlowIT` — the full submission → admin approve → viewer purchase-confirm → real entitlement → admin marks payout paid → contributor sees it flow, end to end. Razorpay *order creation* itself isn't exercised (that's a thin third-party SDK call, and this suite is hermetic — no outbound network calls); instead it inserts the `Purchase` row directly (as it would exist right after a real order) and computes a valid signature with `com.razorpay.Utils.getHash` against a test-only key secret, so `/purchases/confirm`'s real verification logic actually runs.
  - **These run via `mvn verify`, not `mvn test`** — added a `maven-failsafe-plugin` binding specifically for `*IT.java`, kept separate from the fast `@WebMvcTest` slice suite (`mvn test`) since they need Docker to spin up containers. **Not run in this sandbox** (see the running caveat under Known gaps) — this is the first thing to check locally.
- **Spring Boot Actuator** (`spring-boot-starter-actuator`) — `GET /actuator/health` (+ `/actuator/health/liveness`, `/actuator/health/readiness` for container orchestration probes), with real DB and Redis component checks (auto-registered since both starters are already present). Only `health` and `info` are exposed over HTTP at all (`management.endpoints.web.exposure.include`, `application.yml`) — nothing sensitive like `/actuator/env` or `/actuator/beans` is reachable regardless of auth. Component-level detail (is Postgres actually reachable? Redis?) only shows to authenticated admins (`show-details: when-authorized`, `roles: ADMIN` — reuses the same `ROLE_ADMIN` authority `hasRole("ADMIN")` already checks elsewhere); everyone else, including the unauthenticated liveness/readiness probes, just gets `{"status":"UP"}`. This is separate from the existing `GET /api/v1/health` (Phase 1) — that one's what the web app's own banner calls; this one's for infrastructure.
- **Rate limiting** (`RateLimitingFilter`, `api/.../security/`) — a blunt, Redis-backed fixed-window counter on `/api/v1/auth/{login,register,forgot-password,reset-password}` (5-10 requests/minute/IP depending on endpoint), to blunt credential stuffing and mass-registration/email-flooding abuse. Deliberately IP-only, not per-account (would need to parse the request body inside the filter for the email, which isn't worth the complexity yet), and doesn't trust `X-Forwarded-For` (no configured trusted-proxy list — that header is trivially spoofable without one; revisit with `ForwardedHeaderFilter` + a real proxy allowlist once this is actually behind a load balancer/CDN). Wired manually in `SecurityConfig` (not `@Component`) for the same reason `JwtAuthenticationFilter` is — a `@Component` `Filter` would additionally get auto-registered as a blanket servlet filter by Spring Boot, running twice.
- **Error tracking (Sentry)** — `io.sentry:sentry-spring-boot-4` (the Spring Boot 4-specific artifact; `-jakarta` is for Boot 3), configured via `sentry.dsn` (env var `SENTRY_DSN`, blank by default). Same graceful-degradation pattern as Razorpay: blank DSN means the SDK quietly no-ops, nothing crashes, nothing gets sent. Create a free project at sentry.io and set the env var when you want this live — no code changes needed.
- **Structured logging** — `api/src/main/resources/application-prod.yml` (new, activate with `SPRING_PROFILES_ACTIVE=prod`) switches console logging to ECS-formatted JSON, which log aggregators (CloudWatch, ELK, etc.) parse far better than plain text. Deliberately **not** the default — local dev keeps Spring Boot's normal readable console pattern.
- **Dependency vulnerability scanning** — `org.owasp:dependency-check-maven` added behind an opt-in `owasp` Maven profile (`mvn -Powasp verify`), not run on every build since it's slow and needs to sync a vulnerability database over the network. As of the last couple of years the NVD API rate-limits unauthenticated syncs hard enough that you'll likely want a free API key from https://nvd.nist.gov/developers/request-an-api-key and to pass it via `-Dnvd.api.key=...` — the plugin will tell you if you're getting throttled.
- **Security review findings (no code changes needed — already in good shape):**
  - Every request DTO already has Bean Validation annotations (`@NotBlank`, `@Email`, `@Size`, etc.) — this was consistent from Phase 2 onward, confirmed across auth/experience/payment/payout DTOs.
  - Spring Security's default response headers (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, cache-control on sensitive responses, HSTS when served over HTTPS) are active — `SecurityConfig` never calls `.headers(...)` to disable them, so Spring Security's sane defaults stand.
  - All queries go through JPA/JPQL with bound parameters (no string-concatenated SQL anywhere), so standard SQL injection isn't a live concern.
  - Passwords are BCrypt-hashed (`PasswordEncoder` bean in `SecurityConfig`, Phase 2).
  - **One action item for when this deploys**: `corsConfigurationSource()` in `SecurityConfig` currently allows `http://localhost:*` / `http://127.0.0.1:*` only — correct for local dev, but needs updating to the real deployed frontend origin(s) before this is useful anywhere else. There's a comment marking the spot.

### Known gaps worth knowing about (Phase 5)

- **Nothing in this phase has been run.** This sandbox has no JDK 21/Maven (no sudo, no package-manager or direct-download network access), so none of the new Java — the two `*IT.java` suites, the `RateLimitingFilter`, the Actuator wiring — has actually compiled or executed anywhere yet. This is a bigger ask than usual to just "try it and tell me": run `mvn verify` locally (not just `mvn test` — the new IT suites only run under `verify`) and Docker needs to be running for the Testcontainers to start.
- Sentry and structured logging are wired but inert until you set `SENTRY_DSN` and/or deploy with `SPRING_PROFILES_ACTIVE=prod` — nothing to verify locally beyond "the app still starts fine," which running anything at all will confirm.
- No E2E (browser-driven) test suite yet — Testcontainers coverage here is API-level only. Playwright/Cypress against the real web app is a reasonable next addition if it becomes valuable.
- Account-based (not just IP-based) rate limiting on login isn't in place — a distributed credential-stuffing attempt spread across many IPs wouldn't be caught by this.

## Immediate next steps (in order)

1. **Run `mvn verify` locally** (not `mvn test`) with Docker running — this is what actually exercises everything built in Phase 5: both new `*IT.java` suites, the rate limiter, real DB/Redis health checks. Report back anything that fails.
2. **Run the viewer-payment flow locally**: set `RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET` (Test Mode keys) as env vars, restart the backend, `cd web && npm run dev`. Browse to a published experience, hit "Unlock", complete checkout with a [Razorpay test card](https://razorpay.com/docs/payments/payments/test-card-upi-details/).
3. **Run the payout flow locally**: approve an experience as admin (creates a `PENDING` payout), open the "Admin payouts" tab, enter a reference, hit "Mark paid" — confirm it flips to `PAID` and shows up under that contributor's "My payouts" tab.
4. Check `GET /actuator/health` locally (and again once logged in as admin, with the token attached) to see the DB/Redis component detail show up.
5. Optional: create a free Sentry project and set `SENTRY_DSN` to see an error actually show up there (e.g. temporarily break something and hit it).
6. Optional but recommended: set up an ngrok tunnel + a real webhook URL in the Razorpay dashboard, set `RAZORPAY_WEBHOOK_SECRET`, and confirm the payment-confirmation backup path works.
7. Phases 6–8: AWS deploy (this is where `SPRING_PROFILES_ACTIVE=prod` and the real CORS origin update from above actually matter), mobile store release, MVP launch. Full detail in `01-build-roadmap.md`. Mobile scaffolding (`docs/03-setup-guide.md` §5 / `mobile/README.md`) picks back up whenever you're ready.

## Open items to resolve (not code)

1. **★ Legal / NDA risk** — paying people to disclose interview questions can conflict with NDAs/confidentiality. Get a lawyer's view; needs ToS + a content policy (no verbatim proprietary/take-home material) + takedown process. **Highest priority before the submission feature goes live.** (Not legal advice — I'm not a lawyer.)
2. **Payments/tax** — paying contributors makes you a marketplace: KYC, GST/tax reporting, refund/chargeback policy.
3. **Proof documents are sensitive PII** — store privately, encrypt, restrict to admins, define retention/deletion.
4. **Cold-start** — seed initial content; pick a launch niche (e.g. SWE at big tech) first.

## Reference docs

- `01-build-roadmap.md` — full prerequisites + 8-phase roadmap.
- `02-phase0-design.md` — PRD, data model/ERD, flows, full MVP API contract, scope in/out, risks.
- `03-setup-guide.md` — fresh-machine setup + first run.
- `04-handoff.md` — this file.
