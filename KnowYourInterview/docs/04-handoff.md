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

## Immediate next steps (in order)

1. **Run Phase 3 locally**: `docker compose up -d`, `./mvnw spring-boot:run`, `cd web && npm run dev`. Log in, create a draft, add a round, upload a proof file, submit it, then log in as an admin (see bootstrap SQL above) and approve/reject it from the admin tab.
2. **Phase 4 — Payments:** Razorpay unlock flow (turns the current always-teaser public view into a real paid unlock via a real `Entitlement` row) + the actual RazorpayX transfer for the `Payout` rows Phase 3 already creates.
3. Phases 5–8: hardening/observability (Testcontainers-based integration tests, real email delivery, S3 swap for proof storage), AWS deploy, mobile store release, MVP launch. Full detail in `01-build-roadmap.md`. Mobile scaffolding (`docs/03-setup-guide.md` §5 / `mobile/README.md`) picks back up whenever you're ready — revisit before Phase 7, and repeat the auth + submission wiring there once it does.

### Known gaps worth knowing about before Phase 4

- No round-editing endpoint — contributors can add/remove rounds but not edit one in place (delete + re-add). Small addition if it turns out to matter.
- No pagination/edit history on rejected-and-resubmitted experiences — rejecting just sets `status=REJECTED` with a reason; there's no re-submit flow back to `DRAFT` yet.
- `getPublicView`'s "entitled" flag (see above) will need real thought when Phase 4 lands — right now it conflates "owns/reviews this" with "paid for this."

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
