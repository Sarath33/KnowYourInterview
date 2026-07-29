# Multi-stage build: compile with the Maven wrapper against JDK 21, run on a slim JRE.
# Build context is expected to be the `api/` directory (this file's own directory) — it
# has no dependency on anything outside it (see the comment in shared/types.ts usage
# notes elsewhere; the api module only *mirrors* those field names, it doesn't import them).

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Cache dependency resolution in its own layer — only re-runs when pom.xml changes.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN ./mvnw -B dependency:go-offline

COPY src src
# Skip tests here — backend-ci.yml already runs `mvn -B verify` (unit + IT suites) on
# every push. A deploy build re-running the full suite (including Testcontainers IT
# tests, which need Docker-in-Docker) would just slow down/complicate the image build
# for no extra safety.
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Runs as non-root at the process level (see entrypoint.sh) — but the image build and
# container start still need root briefly, because Railway attaches the /app/uploads/proof
# persistent volume at container start, owned by root, which shadows whatever ownership
# was baked into the image at that path. So we can no longer just `USER kyi` here and be
# done: the entrypoint fixes ownership of the *mounted* volume at every boot, then drops
# privileges via su-exec before exec'ing java. See entrypoint.sh for the detail.
RUN addgroup -S kyi && adduser -S kyi -G kyi \
    && apk add --no-cache su-exec

COPY --from=build /build/target/know-your-interview-api-*.jar app.jar
RUN mkdir -p /app/uploads/proof && chown -R kyi:kyi /app

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

# Railway sets PORT at runtime; server.port in application.yml defaults to 8080 for
# local dev. Bind Spring's server.port to whatever Railway hands us.
ENV PROOF_STORAGE_DIR=/app/uploads/proof
EXPOSE 8080

# Deliberately no USER directive here — the container must start as root so the
# entrypoint can chown the freshly-mounted volume. entrypoint.sh drops to the
# unprivileged kyi user via su-exec before running the actual JVM.
ENTRYPOINT ["/entrypoint.sh"]
