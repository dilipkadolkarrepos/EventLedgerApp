# ── Stage 1: Build ──────────────────────────────────────────────────────────
# Maven image provides both JDK 17 and mvn; nothing needs to be pre-installed.
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy the POM first so Maven can download dependencies in a separate layer.
# This layer is cached and only invalidated when pom.xml changes, making
# subsequent rebuilds fast even when only source files are modified.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and produce the runnable JAR (tests are run in CI, not here).
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ────────────────────────────────────────────────────────
# Alpine-based JRE keeps the final image small (~180 MB vs ~500 MB for the JDK).
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=builder /build/target/event-ledger-0.0.1-SNAPSHOT.jar app.jar

# Document the port; actual binding is in docker-compose.yml.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
