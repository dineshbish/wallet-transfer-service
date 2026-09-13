# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first: only re-download when the pom changes.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# curl is used by the HEALTHCHECK below.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app --home /app app

COPY --from=build /build/target/*.jar app.jar
RUN chown -R app:app /app
USER app

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:${PORT:-8080}/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
