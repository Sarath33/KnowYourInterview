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
- `api/.../config/WebConfig.java` — dev CORS config allowing `localhost:*` origins so the Vite dev server can call the API.

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

## Immediate next steps (in order)

1. **Run Phase 2 locally**: `docker compose up -d`, `./mvnw spring-boot:run`, `cd web && npm run dev`. Register an account, confirm login/logout work, check the browser Network tab for the access/refresh tokens.
2. **Phase 3 — Core domain:** structured submission form, admin review queue, approve/reject → publish, browse with teasers. This is where protected routes actually get exercised (contributor endpoints require `ROLE_USER`; admin review requires `ROLE_ADMIN`).
3. **Phase 4 — Payments:** Razorpay unlock flow + entitlement gating; one-time contributor payout.
4. Phases 5–8: hardening/observability (this is also where the Testcontainers-based auth integration test and real email delivery belong), AWS deploy, mobile store release, MVP launch. Full detail in `01-build-roadmap.md`. Mobile scaffolding (`docs/03-setup-guide.md` §5 / `mobile/README.md`) picks back up whenever you're ready — revisit before Phase 7, and repeat the auth wiring there once it does.

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
