# Setup Guide — Fresh Personal Machine

Follow this top to bottom on your personal computer. It takes you from a clean machine to a running backend. Estimated time: 45–90 min (mostly downloads).

> The Phase 1 skeleton (monorepo, Spring Boot API, Flyway schema, docker-compose) has already been generated for you under this project folder — you do not need `setup-project.sh`. Just install the tools below and run it.

---

## 1. Install the tools

Install these four. Pick the column for your OS.

| Tool | Why you need it | macOS | Windows |
|---|---|---|---|
| **JDK 21** (Temurin) | Runs the Spring Boot backend | [adoptium.net](https://adoptium.net) → "Temurin 21 (LTS)", or `brew install --cask temurin@21` | Download the `.msi` from [adoptium.net](https://adoptium.net) and run it |
| **Node.js 22 LTS** | Runs the web + mobile apps | [nodejs.org](https://nodejs.org) → "LTS", or `brew install node@22` | `.msi` from [nodejs.org](https://nodejs.org) |
| **Docker Desktop** | Runs Postgres + Redis locally | [docker.com](https://www.docker.com/products/docker-desktop) | Same link (needs WSL2 — installer sets it up) |
| **Git** | Version control | Preinstalled, or `brew install git` | [git-scm.com](https://git-scm.com) |

**IDE (choose one, recommended):**
- **IntelliJ IDEA Community** (free) — best for the Java/Spring backend. [jetbrains.com/idea](https://www.jetbrains.com/idea/download). Has built-in Maven support, so no separate Maven install needed.
- **VS Code** (free) — good for the React web + Expo mobile apps. [code.visualstudio.com](https://code.visualstudio.com).

**Verify installs** (open a terminal / PowerShell):
```bash
java -version     # should say 21
node -v           # should say v22.x
docker -v         # any recent version
git --version
```

---

## 2. Create accounts (free tiers are fine)

| Account | Purpose | Link |
|---|---|---|
| **GitHub** | Store/back up your code | [github.com](https://github.com) |
| **Razorpay** (India) | Viewer payments + contributor payouts | [razorpay.com](https://razorpay.com) — use **Test Mode** while building |
| **AWS** | Cloud hosting (Phase 6) | [aws.amazon.com](https://aws.amazon.com) — set a billing alarm immediately |
| **Sentry** | Error tracking (later) | [sentry.io](https://sentry.io) |

> Razorpay live payments/payouts need business KYC (and RazorpayX for payouts). You do **not** need that to build — Test Mode gives fake keys and test cards. Do KYC only near launch.

---

## 3. Put it under version control

The project folder already exists with the Phase 1 skeleton in it. From the project root:
```bash
cd KnowYourInterview
git init
git add -A
git commit -m "Initial import: Phase 1 walking skeleton"
# create an empty repo on github.com first, then:
git remote add origin https://github.com/<your-username>/know-your-interview.git
git branch -M main
git push -u origin main
```

---

## 4. First run

**Start the database:**
```bash
docker compose up -d          # Postgres :5432, Redis :6379
docker ps                     # confirm kyi-postgres and kyi-redis are up
```

**Run the backend** — easiest via IntelliJ:
1. Open the `api/` folder in IntelliJ IDEA.
2. It imports the Gradle project and downloads dependencies (first time is slow).
3. Open `KnowYourInterviewApplication.java` → click the green ▶ next to `main`.

**Or via terminal:**
```bash
cd api
mvn -N wrapper:wrapper -Dmaven=3.9.9   # one-time; creates ./mvnw
./mvnw spring-boot:run                  # Mac/Linux
# mvnw.cmd spring-boot:run                # Windows
```
(Needs a system Maven install just for that one-time wrapper step — `brew install maven` / [maven.apache.org](https://maven.apache.org/download.cgi). After that, `./mvnw` is self-contained.)

**Verify:**
```bash
curl http://localhost:8080/api/v1/health
# -> {"status":"UP","service":"know-your-interview-api","timestamp":"..."}
```
On startup watch for Flyway applying `V1__init.sql` (creates your tables) and `Started KnowYourInterviewApplication`.

---

## 5. Generate the web and mobile apps (once)

```bash
# Web (from repo root)
npm create vite@latest web -- --template react-ts
cd web && npm install && npm run dev      # http://localhost:5173

# Mobile (from repo root)
npx create-expo-app@latest mobile
cd mobile && npx expo start
```

Then wire each to call `GET /api/v1/health` (examples in `web/README.md` and `mobile/README.md`). That completes the Phase 1 walking skeleton on all three surfaces.

---

## 6. Troubleshooting

- **Backend won't start, DB error** → Postgres isn't ready. `docker ps`; if missing, `docker compose up -d` again.
- **Flyway "schema not empty" error** → reset the DB: `docker compose down -v` then `docker compose up -d` (⚠️ deletes local data).
- **Port already in use (8080/5432)** → stop the other process or change the port in `application.yml` / `docker-compose.yml`.
- **Maven dependency download blocked / slow** → normal on first run; if it fails entirely, check network/firewall.
- **Mobile can't reach API on a physical phone** → `localhost` is the phone itself. Use your computer's LAN IP (e.g. `http://192.168.1.x:8080`) or an Expo tunnel.

---

## 7. After it runs

Open `04-handoff.md` — it says exactly where we are and the next steps (Phase 2: authentication). When ready to continue, tell me you've got the health endpoint returning `UP` and we'll pick up from there.
