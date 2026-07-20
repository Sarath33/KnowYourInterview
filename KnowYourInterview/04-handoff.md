# Handoff — Continue Here

Read this first when you resume on your personal machine.

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
| Backend | Java 21 · Spring Boot 4.1 · Gradle |
| Web | React 19 + Vite + TypeScript |
| Mobile | Expo / React Native |
| Database | PostgreSQL (+ Redis) |
| API style | REST (`/api/v1`) |
| Payments | Razorpay (India) |
| Cloud (later) | AWS |
| Payout model | One-time flat fee on publish |
| Viewer pricing | Pay-per-experience |
| Verification | Proof upload + admin review |

## What's already built (Phase 1 — walking skeleton)

- Monorepo structure + `docker-compose.yml` (Postgres 17, Redis 7).
- Spring Boot API with `GET /api/v1/health` and a passing test.
- **Flyway `V1__init.sql`** — full initial DB schema: users, experiences, experience_rounds, proof_documents, purchases, entitlements, payout_accounts, payouts, review_logs.
- `shared/types.ts` — TypeScript API contract for web + mobile.
- `web/` and `mobile/` = README placeholders (generate with official tools).

**Status:** backend is written but has not been compiled/run yet — your first job is to run it (see `03-setup-guide.md`) and confirm the health endpoint returns `UP`.

## Immediate next steps (in order)

1. **Set up your machine** and run the backend — `03-setup-guide.md`. Confirm `/api/v1/health` → `UP`.
2. **Generate web + mobile apps** and wire each to the health endpoint (finishes Phase 1 across all three surfaces).
3. **Phase 2 — Authentication:** register/login/logout, password reset, JWT, roles (USER/CONTRIBUTOR/ADMIN), protected routes on web + mobile.
4. **Phase 3 — Core domain:** structured submission form, admin review queue, approve/reject → publish, browse with teasers.
5. **Phase 4 — Payments:** Razorpay unlock flow + entitlement gating; one-time contributor payout.
6. Phases 5–8: hardening/observability, AWS deploy, mobile store release, MVP launch. Full detail in `01-build-roadmap.md`.

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
- `setup-project.sh` — run once to recreate the whole project folder.
