# Build Roadmap — Java · Spring Boot · React (Web + Mobile SaaS on Cloud)

_Prepared July 2026. Tailored for an experienced developer building "Know Your Interview". Versions verified current as of this date._

---

## 1. Recommended stack (current stable, July 2026)

| Layer | Choice | Version | Notes |
|---|---|---|---|
| Language (backend) | Java | **21 LTS** | Spring Boot 4.1 needs Java 17+, supports up to 26. Stick to an LTS. |
| Backend framework | Spring Boot | **4.1.x** | Built on Spring Framework 7. |
| Build tool | Gradle (Kotlin DSL) | latest | Fast; IntelliJ bundles it. |
| Web frontend | React | **19.2.x** | Vite as the build tool. |
| Mobile | React Native + Expo | RN **0.85+**, **Expo SDK 56** | Shares logic/types with web; ships to both stores. |
| Language (frontend) | TypeScript | latest | Non-negotiable for maintainability. |
| Database | PostgreSQL | 16/17 | Managed on cloud (RDS/Cloud SQL). |
| Cache/queue | Redis | latest | Sessions, rate limiting, background jobs. |
| Auth | Spring Security + JWT | — | Provider (Auth0/Cognito) optional for speed. |
| Payments | **Razorpay** (India) | — | RazorpayX for contributor payouts. |
| Container | Docker | latest | Everything runs in containers from day one. |
| Cloud | AWS | — | ECS/Fargate + RDS + S3. |

**Shape:** one Spring Boot REST API serves both the React web app and the Expo mobile app. Build business logic once, consume from two clients.

---

## 2. Prerequisites

### Local environment
- **JDK 21** (via SDKMAN! or Temurin installer)
- **Node.js 22+** (via nvm/fnm)
- **Docker Desktop** (Postgres, Redis, local builds)
- **Git** + SSH key
- **IDE**: IntelliJ IDEA (backend) + VS Code (frontend)
- **API client**: Bruno / Insomnia / Postman
- **Mobile**: Xcode and/or Android Studio; Expo Go app on your phone

### Accounts & services (free tiers to start)
- **GitHub** (source, CI/CD, issues)
- **AWS** (set a billing alarm on day one)
- **Domain** (buy early)
- **Razorpay** (payments + payouts; Test Mode while building)
- **Sentry** (errors)
- **Email/SMS** (Postmark/SendGrid, Twilio) when needed
- **App store accounts** — Apple Developer ($99/yr), Google Play ($25 once) — register early; Apple review is slow

### Knowledge to refresh
Spring Boot 4 / Spring Framework 7 changes; JPA/Hibernate + Flyway migrations; React 19 + hooks; REST design; containers + one cloud's core primitives.

---

## 3. Target architecture

```
                    ┌──────────────────┐
   React Web (Vite) │                  │
   ─────────────────▶│                  │
                     │  Spring Boot 4   │────▶ PostgreSQL (managed)
   Expo Mobile app   │  REST/JSON API   │────▶ Redis (cache/queue)
   ─────────────────▶│  (business logic)│────▶ Object storage (S3)
                     │                  │────▶ Razorpay (payments/payouts)
                     └──────────────────┘
                              │
                     Observability: Sentry + logs/metrics
```

One API, two clients. Shared TypeScript package for API types. Stateless API behind a load balancer; state in Postgres/Redis. Everything containerized; infra as code (Terraform) past the prototype.

---

## 4. Phased roadmap

Durations assume part-time solo pace.

**Phase 0 — Kickoff & design (0.5–1 wk):** domain model, core user flow, MVP scope; PRD + ERD + endpoint list + wireframes. _(Done — see 02-phase0-design.md.)_

**Phase 1 — Walking skeleton (1 wk):** monorepo scaffolded; Docker Compose Postgres+Redis; one health path web↔API↔mobile; CI. _(Scaffold done; first run pending.)_

**Phase 2 — Auth & accounts (1–1.5 wk):** signup/login/logout/reset, JWT, roles, protected routes on web + mobile.

**Phase 3 — Core domain (2–4 wk):** structured submission form; admin review queue; approve/reject → publish; browse with teasers; migrations, validation, tests.

**Phase 4 — Payments & supporting features (1–2 wk):** Razorpay pay-per-experience unlock + entitlement gating; one-time contributor payout; notifications, file uploads, search.

**Phase 5 — Hardening & observability (1 wk):** tests (unit/integration/E2E), Sentry, logging, health probes, security pass (validation, rate limiting, secrets, dependency scan, OWASP basics).

**Phase 6 — Cloud deployment (1–1.5 wk):** containerize; Terraform for RDS, ECS Fargate, load balancer, secrets, S3, DNS+TLS; CI/CD staging→prod.

**Phase 7 — Mobile store release (1–2 wk, overlaps):** Expo EAS build/submit iOS + Android; store listings, privacy labels; Apple review buffer.

**Phase 8 — MVP launch (ongoing):** beta with real users, feedback loop, analytics, iterate.

**Rough total to MVP:** ~10–16 weeks part-time.

---

## 5. How we work together

Kickoff produces Phase 0 artifacts (done). Per-phase loop: I write code and explain the non-obvious parts; you review, run locally, steer. Each phase ends with tests + review before advancing. You own product decisions, accounts/billing, app-store identity. I own implementation, boilerplate, wiring, surfacing trade-offs.
