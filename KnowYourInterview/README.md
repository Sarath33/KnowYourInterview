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

Register an account at `http://localhost:5173` to try auth end to end. To make yourself an admin: `UPDATE users SET is_admin = true WHERE email = '...';` (see `docs/04-handoff.md`).

## Status

Phase 1 (web-first walking skeleton) and Phase 2 (JWT authentication) are done — backend + web app confirmed running locally, pushed to GitHub. Mobile deferred. See `docs/04-handoff.md` for the full picture and what's next (Phase 3: core domain).
