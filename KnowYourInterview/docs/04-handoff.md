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

**Status: backend confirmed running (`/api/v1/health` → `UP`, Flyway applied); web app confirmed running and talking to the API.** Mobile not started.

## Immediate next steps (in order)

1. **Push to GitHub** (`docs/03-setup-guide.md` §3) so CI runs and you have a backup. Not yet done.
2. **Phase 2 — Authentication:** register/login/logout, password reset, JWT, roles (USER/CONTRIBUTOR/ADMIN), protected routes on web. (Mobile auth deferred until mobile scaffolding resumes.)
3. **Phase 3 — Core domain:** structured submission form, admin review queue, approve/reject → publish, browse with teasers.
4. **Phase 4 — Payments:** Razorpay unlock flow + entitlement gating; one-time contributor payout.
5. Phases 5–8: hardening/observability, AWS deploy, mobile store release, MVP launch. Full detail in `01-build-roadmap.md`. Mobile scaffolding (`docs/03-setup-guide.md` §5 / `mobile/README.md`) picks back up whenever you're ready — revisit before Phase 7.

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
