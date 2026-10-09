# =============================================================================
# OPACA Cost-Aware Gateway — Dockerfile
# =============================================================================
# Multi-stage build:
#   Stage 1 (builder): compiles and packages the Spring Boot fat JAR
#   Stage 2 (runtime): minimal JRE image, runs as non-root
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src

# Download dependencies offline first (cached layer)
RUN apk add --no-cache maven && \
    mvn dependency:go-offline -q

# Build the fat JAR, skip tests (tests run in CI)
RUN mvn package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="OPACA Cost-Aware Gateway"
LABEL org.opencontainers.image.description="Cost-aware API gateway with three-tier LLM routing for OPACA"
LABEL org.opencontainers.image.version="1.0.0"

# Non-root user for security
RUN addgroup -S gateway && adduser -S gateway -G gateway
USER gateway

WORKDIR /app

# Copy only the repackaged fat JAR
COPY --from=builder /build/target/cost-gateway-*.jar app.jar

# Spring Boot actuator port + main API port (same: 8080)
EXPOSE 8080

# JVM tuning for containers: honour cgroup memory limits
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+HeapDumpOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
