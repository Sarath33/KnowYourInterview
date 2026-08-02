# Deployment — Railway

Deployed 2026-07-26. Live URLs:

- **Web:** https://web-production-b94af.up.railway.app
- **API:** https://api-production-3c744.up.railway.app (health: `/actuator/health`)

Project: `know-your-interview` in Railway, single `production` environment, four services — `api`, `web`, `Postgres`, `Redis`.

## How it's built

Both `api` and `web` deploy from the same GitHub repo (`Sarath33/KnowYourInterview`, branch `main`) via Railway's GitHub-App integration, using custom Dockerfiles rather than Railway's auto-detected buildpack (Railpack) — see "Repo layout gotcha" below for why that distinction mattered.

- **`api/Dockerfile`** — multi-stage: Maven wrapper + JDK 21 build (`./mvnw -B package -DskipTests` — tests already run in `backend-ci.yml` on every push, so skipping here just avoids re-running the full suite, including Testcontainers, inside the image build), then a slim `eclipse-temurin:21-jre-alpine` runtime. Runs as a non-root user. Reads `PORT` at startup (`java -jar app.jar --server.port=${PORT:-8080}`) since Railway assigns that dynamically.
- **`web/Dockerfile`** — multi-stage: `node:22-alpine` build (`tsc -b && vite build`) → `nginx:1.27-alpine` serving the static `dist/`. `web/nginx.conf` has a SPA fallback (`try_files $uri $uri/ /index.html`) so client-side routes like `/library` or `/submissions/:id` don't 404 on a hard refresh — required because the frontend uses real History-API routing (`web/src/lib/router.tsx`), not hash routing. `VITE_API_BASE_URL` is baked in at **build time** via a Docker `ARG` (Vite inlines `import.meta.env.*` at build, not runtime), so changing the API's domain means rebuilding `web`, not just restarting it.
- **Postgres / Redis** — plain `postgres:17-alpine` / `redis:7-alpine` images (not Railway's managed Postgres/Redis plugins), created via the Railway MCP's `create-service` with an image rather than a template. This means the convenience env vars those plugins normally auto-generate (`PGHOST`, `DATABASE_URL`, etc.) **don't exist** here — `api`'s `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` reference `Postgres`'s own `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` variables and `RAILWAY_PRIVATE_DOMAIN` directly (see env vars below).

## Repo layout gotcha (read before touching root directories)

The GitHub repo's actual root contains the project nested one level down, at `KnowYourInterview/` — a side effect of how the repo was first pushed (from `~/Documents`, not from inside the project folder itself). So on Railway, each service's **Root Directory** is set accordingly, not to the repo root:

| Service | Root Directory | Dockerfile Path |
|---|---|---|
| `api` | `KnowYourInterview/api` | `Dockerfile` |
| `web` | `KnowYourInterview` | `web/Dockerfile` |

`web`'s root directory is the parent (`KnowYourInterview`, not `KnowYourInterview/web`) because `web/src` imports `../../shared/types.ts` — the Docker build context has to include `shared/` as a sibling of `web/`, so `web/Dockerfile` deliberately `COPY`s both `web/` and `shared/` relative to that wider root (see the comment at the top of `web/Dockerfile`).

If the repo ever gets re-pushed from inside the `KnowYourInterview` folder itself (i.e. the project becomes the true repo root), these two Root Directory settings need to change to `api` and `/` (with `dockerfilePath: web/Dockerfile`) respectively, or every build will fail with "couldn't locate the dockerfile" / Railpack falling back to auto-detection.

## Environment variables

Set on the `api` service (Railway reference-variable syntax resolves these from the `Postgres`/`Redis` services at deploy time):

| Variable | Value | Notes |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://${{Postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{Postgres.POSTGRES_DB}}` | Private network — Postgres has no public domain. |
| `DB_USERNAME` | `${{Postgres.POSTGRES_USER}}` | |
| `DB_PASSWORD` | `${{Postgres.POSTGRES_PASSWORD}}` | |
| `REDIS_URL` | `redis://${{Redis.RAILWAY_PRIVATE_DOMAIN}}:6379` | No auth — matches local dev's unauthenticated docker-compose Redis. Revisit if this ever needs to be internet-facing. |
| `SPRING_PROFILES_ACTIVE` | `prod` | Switches to ECS-formatted JSON console logs (`application-prod.yml`). |
| `JWT_SECRET` | *(generated, 256-bit)* | Overrides the `dev-only-change-me...` default in `application.yml`. |
| `CORS_ALLOWED_ORIGINS` | `https://web-production-b94af.up.railway.app` | See `SecurityConfig.java` — comma-separated if it ever needs more than one origin. |
| `RAZORPAY_KEY_ID` / `RAZORPAY_KEY_SECRET` / `RAZORPAY_WEBHOOK_SECRET` | *(not set)* | Payments are effectively disabled until these are added — see Known gaps. |
| `GOOGLE_CLIENT_ID` | *(not set)* | Enables "Sign in with Google" (`POST /api/v1/auth/google`) when set — same graceful-degradation pattern as Razorpay above; the endpoint returns 503 until this is added. See "Setting up Google Sign-In" below. |
| `TRUST_FORWARDED_FOR` | `true` | **Required on Railway.** Tells `RateLimitingFilter` to bucket requests by the left-most `X-Forwarded-For` hop instead of `getRemoteAddr()`. Without it every request appears to come from Railway's edge, so the per-IP auth limits become global — 10 logins/minute shared across all users, not per user. Leave it unset (false) anywhere the app is directly internet-facing, where the header is attacker-controlled. |
| `WEB_BASE_URL` | `https://web-production-b94af.up.railway.app` | Origin the emailed confirmation and password-reset links are built against. Defaults to `http://localhost:5173`, so without this every link points people at their own machine. |
| `MAIL_HOST` | *(your provider's SMTP host)* | **Unset = no email is sent** — messages are written to the API log instead (see "Email delivery" below). Required for registration confirmation and password reset to reach anyone. |
| `MAIL_PORT` | `587` | 587 + STARTTLS is the near-universal default. Some providers also offer 465 with implicit TLS. |
| `MAIL_USERNAME` / `MAIL_PASSWORD` | *(provider SMTP credentials)* | For Postmark these are both the Server API token; SendGrid uses the literal username `apikey` plus the key; Resend uses `resend` plus the key. Check your provider's SMTP page. |
| `MAIL_FROM_ADDRESS` | e.g. `no-reply@yourdomain.com` | Must be at a domain the provider has verified for sending, or mail is rejected or spam-filed regardless of everything else being right. |
| `MAIL_FROM_NAME` | `Know Your Interview` | Display name on the From: header. Optional. |
| `ADMIN_BOOTSTRAP_SECRET` | *(generated)* | Enables `POST /api/v1/auth/bootstrap-admin` (`{email, secret}` → promotes an existing, already-registered account to admin) — same graceful-degradation pattern, 503 until set. Exists to solve the chicken-and-egg problem of minting the very first admin on a fresh environment without direct DB access. Rate-limited (5/min/IP) and compared with a constant-time check; rotate/unset it once you have your first admin. |

Set on the `Postgres` service:

| Variable | Value | Notes |
|---|---|---|
| `POSTGRES_DB` | `kyi` | |
| `POSTGRES_USER` | `kyi` | |
| `POSTGRES_PASSWORD` | *(generated)* | |
| `PGDATA` | `/var/lib/postgresql/data/pgdata` | **Required** — see "Volume gotcha" below. Without this, Postgres refuses to start on a freshly-mounted volume. |

Set on the `web` service:

| Variable | Value | Notes |
|---|---|---|
| `VITE_API_BASE_URL` | `https://api-production-3c744.up.railway.app` | Build-time only (see above) — changing this requires a rebuild, not just a redeploy. |
| `VITE_GOOGLE_CLIENT_ID` | *(not set)* | Build-time only, same as above. The Google "Sign in with" button (`GoogleSignInButton.tsx`) silently renders nothing if this is unset — email/password auth still works either way. |

## Volumes

- `Postgres` → `postgres-data` mounted at `/var/lib/postgresql/data` (persists across redeploys/restarts).
- `api` → `api-uploads-proof` mounted at `/app/uploads/proof` (contributor proof-document uploads; see Known gaps — this should move to S3 before it needs to scale past one instance).

**Volume gotcha:** mounting a fresh Railway volume directly at `/var/lib/postgresql/data` leaves a `lost+found` directory there (an artifact of the filesystem), and Postgres' `initdb` refuses to initialize a non-empty directory — it crash-loops with `initdb: error: directory "/var/lib/postgresql/data" exists but is not empty`. Fixed via the standard Postgres Docker image workaround: set `PGDATA` to a subdirectory *within* the mount (`/var/lib/postgresql/data/pgdata`) rather than the mount point itself.

## Setting up Google Sign-In (2026-07-26)

Added alongside email/password auth (`POST /api/v1/auth/google`), not instead of it — existing
accounts and the register/login forms are unaffected. It's off (503) until `GOOGLE_CLIENT_ID`
(api) and `VITE_GOOGLE_CLIENT_ID` (web) are both set to the same OAuth Client ID. Steps to
create one, done once in Google Cloud Console:

1. Go to [console.cloud.google.com](https://console.cloud.google.com/), create a project (or
   pick an existing one).
2. **APIs & Services → OAuth consent screen** — choose "External", fill in the app name and
   support email, add your own email as a test user if the app stays in "Testing" mode
   (fine for now; "Publishing" it removes the 100-test-user cap but requires a Google review
   for some scopes — not needed here since this only requests basic profile/email).
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID** — Application
   type **Web application**. Under "Authorized JavaScript origins", add every origin the
   button will actually be loaded from:
   - `https://web-production-b94af.up.railway.app` (production)
   - `http://localhost:5173` (Vite dev server)
   No redirect URI is needed — Google Identity Services' button flow returns the credential
   directly to the page via a JS callback, it doesn't navigate through a redirect.
4. Copy the generated Client ID (ends in `.apps.googleusercontent.com`). Set it as
   `GOOGLE_CLIENT_ID` on the `api` service and `VITE_GOOGLE_CLIENT_ID` on the `web` service
   in Railway, then rebuild `web` (build-time var, see above) and redeploy `api`.

How it works server-side: `GoogleSignInVerifier` (api) verifies the ID token's signature
against Google's published JWKS, plus issuer/audience/expiry — no call to Google's tokeninfo
endpoint, no per-login network round-trip. `AuthService.googleLogin` then finds-or-creates a
user: matches an existing linked Google account first, falls back to linking a Google sign-in
to an existing email/password account with the same *verified* email (so registering normally
and later using "Sign in with Google" doesn't create a duplicate account), and only creates a
brand-new password-less account if neither matches. See `V6__add_google_auth.sql` — `users`
gained a nullable `google_sub` column and `password_hash` became nullable (Google-only
accounts have no password).

<!-- deploy-sync-check: 2026-07-26T12:45Z — trivial marker to confirm Railway is
     building the actual latest commit on main, not a stale cached one. Safe to remove
     once confirmed; harmless if left in place. -->

## Promoting the first admin

There's no DB browser for the raw `postgres:17-alpine` image service (Railway's built-in "Data" tab is a managed-Postgres-plugin feature, not available here). Promoted the first admin account by connecting to Postgres directly and running the same `UPDATE users SET is_admin = true WHERE email = '...'` from `04-handoff.md`'s local-dev instructions.

## Healthcheck

`api`'s healthcheck path is **`/actuator/health`** with a 300s timeout (see the service config).
That's the *aggregate* endpoint, so **every registered health indicator can fail a deploy** —
the app starting successfully isn't enough. That's a sharper edge than it looks: adding a
starter to `pom.xml` can silently add an indicator, and a dependency on something non-critical
then gets a veto over whether the service is considered alive. That's exactly how the mail
indicator broke three deploys in a row (see Known gaps).

Two ways to keep that from recurring, in order of preference:

1. **Keep non-critical indicators off** — the current approach. `management.health.mail.enabled:
   false`. Explicit, and keeps `/actuator/health` meaningful for the things that genuinely
   matter (Postgres, Redis).
2. **Point the healthcheck at `/actuator/health/readiness` instead.** The readiness group only
   contains the readiness state, so it answers "can this instance serve traffic" rather than "is
   every dependency reachable" — immune to this whole class of problem by construction. Arguably
   the more correct question for a platform healthcheck, since restarting a container does
   nothing about a down database anyway. Not done here because it changes what a failed deploy
   means, which is worth a deliberate decision rather than a drive-by change.

If a deploy fails while the logs show `Started KnowYourInterviewApplication`, the healthcheck is
where to look first — hit `/actuator/health` with an admin token to see the component breakdown
(`show-details: when-authorized`) and find which one is `DOWN`.

## Deploying a change

Push to `main` on GitHub, then trigger a Railway deploy. **`redeploy` (the "redeploy" action on an existing deployment) replays that deployment's original build — including whatever commit and service config were live when it was first created — not the current HEAD.** This tripped things up repeatedly during initial setup: after pushing a fix, several `redeploy` calls kept rebuilding the same old broken commit. What actually works to deploy fresh code:

- Make any (even trivial) change via `update-service`, then call `accept-deploy` to commit staged changes — this reliably re-fetches the latest commit.
- Or use the Railway agent/dashboard's own "Deploy latest" action rather than "Redeploy" on an old deployment.

If a deploy comes back green but the site still looks stale, check the deployment's recorded `commitHash` (via `get-logs`/deployment info) before assuming the config or code is wrong — it may just be re-running an old snapshot.

## Smoke test (last run 2026-07-26, all passing)

- `GET https://web-production-b94af.up.railway.app/` → serves the SPA shell (`<title>Know Your Interview</title>`).
- `GET https://api-production-3c744.up.railway.app/api/v1/experiences?page=0&size=1` → `200`, valid JSON, confirms API ↔ Postgres connectivity.
- `GET https://api-production-3c744.up.railway.app/actuator/health` → `200`.

Not yet smoke-tested: register/login, submit-experience flow, Razorpay checkout (blocked on Razorpay keys — see below).

## Known gaps

- **Actuator's mail health indicator is disabled on purpose** (`management.health.mail.enabled: false`). Adding `spring-boot-starter-mail` auto-registers it whenever a `JavaMailSender` bean exists — which is always here, since `spring.mail.host: ${MAIL_HOST:}` leaves the property present-but-empty and the auto-configuration only checks for presence. It then opened an SMTP connection to a blank host on every health check, failed, and took the aggregate `/actuator/health` to `DOWN`, i.e. a 503 on the exact path Railway's healthcheck polls. Every deploy after the email feature was marked FAILED while the app booted perfectly in 7.5s. Don't re-enable it without also making the healthcheck immune (see "Healthcheck" below).
- **Email delivery is configured by env var, and unset means nothing sends.** The app talks SMTP (`spring-boot-starter-mail`), so any provider works — Postmark, SendGrid, Resend, Mailgun. With `MAIL_HOST` unset, `EmailConfig` falls back to `LoggingEmailSender`, which writes the whole message (including the link) to the API log rather than sending it: fine locally, not fine in production, where it means confirmation and reset links only reach whoever can read the logs. Setting it up is three steps: create the provider account, verify a sending domain, then set `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` / `MAIL_FROM_ADDRESS` (plus `WEB_BASE_URL`, or the links will point at localhost). No redeploy of `web` needed — these are all API-side.
- **`TRUST_FORWARDED_FOR` must be set here.** See the env var table — without it the auth rate limits apply to the whole user base collectively rather than per client, because every request arrives from Railway's edge IP.
- **Razorpay isn't configured** (`RAZORPAY_KEY_ID`/`RAZORPAY_KEY_SECRET`/`RAZORPAY_WEBHOOK_SECRET` unset) — the unlock-purchase flow will fail with a clear error the moment someone tries to check out, everything else works fine. Add Test Mode keys first, then a webhook pointed at `https://api-production-3c744.up.railway.app/api/v1/payments/webhook` before flipping to live keys.
- **Webhook still untested against real Razorpay** — same gap noted in `04-handoff.md`'s Phase 4 section, now actually possible to close since there's a real public URL for Razorpay's dashboard to point at.
- **Proof-document storage is a single Railway volume, not S3.** Fine at this scale; revisit (`ProofStorageService` already an interface for exactly this swap) before running more than one `api` instance, since a volume isn't shared across replicas.
- **No custom domain** — both services are on Railway's `*.up.railway.app` subdomains.
- **Redis has no auth**, reachable only over Railway's private network (not exposed publicly) — acceptable for now, revisit if that ever changes.
- **Sentry (`SENTRY_DSN`) not set** — errors aren't being tracked anywhere yet; the SDK is wired and just needs the env var (see `04-handoff.md` Phase 5).
- **Google Sign-In isn't configured yet** (`GOOGLE_CLIENT_ID`/`VITE_GOOGLE_CLIENT_ID` unset) — the button doesn't render on `web`, and `POST /api/v1/auth/google` returns 503 if called directly. See "Setting up Google Sign-In" above.
