# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM gradle:8.10-jdk17 AS build
WORKDIR /app

# Cache dependencies
COPY build.gradle settings.gradle ./
RUN gradle dependencies --no-daemon || true

COPY src ./src
RUN gradle bootJar --no-daemon

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# curl for the container healthcheck; run as non-root
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 appuser
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health >/dev/null 2>&1 || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
