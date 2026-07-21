# Know Your Interview

A verified marketplace for structured interview experiences. Contributors submit real interview experiences and get a one-time flat fee once an admin verifies and publishes them; viewers pay per experience to unlock and read.

Full product design: [`docs/02-phase0-design.md`](docs/02-phase0-design.md).
Full roadmap: [`docs/01-build-roadmap.md`](docs/01-build-roadmap.md).
Where things stand right now: [`docs/04-handoff.md`](docs/04-handoff.md).

## Stack

Java 21 · Spring Boot 4.1 · Maven | React 19 + Vite + TypeScript | Expo/React Native | PostgreSQL + Redis | Razorpay (India) | AWS (later)

## Layout

```
api/        Spring Boot REST API (source of truth for business logic)
web/        React web app, wired to the API (auth included)
mobile/     Expo mobile app (not generated yet — see mobile/README.md)
shared/     TypeScript API contract types, shared by web + mobile
docs/       Product design, roadmap, setup guide, handoff notes
```

## Quickstart

See [`docs/03-setup-guide.md`](docs/03-setup-guide.md) for full fresh-machine setup. Short version, once JDK 21 / Docker / Node are installed:

```bash
docker compose up -d                     # Postgres + Redis
cd api && ./mvnw spring-boot:run         # http://localhost:8080/api/v1/health
cd ../web && npm run dev                 # http://localhost:5173
```

Register an account at `http://localhost:5173` to try it end to end: create a draft submission, add a round, upload a proof file, submit it for review. To make yourself an admin (needed to approve/reject from the "Admin review" tab): `UPDATE users SET is_admin = true WHERE email = '...';` (see `docs/04-handoff.md`).

To try the paid unlock flow, set `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` (Test Mode keys from your Razorpay dashboard) as env vars before starting the API, publish an experience as admin, then "Unlock" it from the browse tab as a different (viewer) account. Contributor payouts are a manual-batch process for now (no RazorpayX account yet) — approve an experience as admin, then use the "Admin payouts" tab to record it once you've sent the money yourself.

To run the full backend test suite including real-infra integration tests (needs Docker running): `cd api && ./mvnw verify` (plain `./mvnw test` runs the fast unit + mocked-service slice tests only — no Docker needed).

To run the frontend test suite: `cd web && npm install && npm test` (add `npm run test:watch` while iterating, or `npm run test:coverage` for a coverage report).

## Status

Phases 1–5 are done for web (walking skeleton, JWT auth, submission/review/browse flow, Razorpay viewer-side unlock payments, manual-batch contributor payouts, Testcontainers integration tests, Actuator health checks, rate limiting, Sentry + structured logging, dependency scanning) — confirmed running locally, pushed to GitHub. A visual redesign (React components matching a design handoff) and a testing-foundation pass (service-layer unit tests for all 5 backend services, a Vitest/RTL frontend test harness with component tests for the auth/unlock/admin-review flows, and a frontend CI workflow) are done but not yet verified locally — see `docs/04-handoff.md`. Live RazorpayX transfers not built (needs a Current Account with separate business KYC approval you don't have yet). Mobile deferred. See `docs/04-handoff.md` for the full picture and what's next.
