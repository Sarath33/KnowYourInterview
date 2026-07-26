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

# Runs as non-root.
RUN addgroup -S kyi && adduser -S kyi -G kyi
COPY --from=build /build/target/know-your-interview-api-*.jar app.jar
RUN mkdir -p /app/uploads/proof && chown -R kyi:kyi /app
USER kyi

# Railway sets PORT at runtime; server.port in application.yml defaults to 8080 for
# local dev. Bind Spring's server.port to whatever Railway hands us.
ENV PROOF_STORAGE_DIR=/app/uploads/proof
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]
