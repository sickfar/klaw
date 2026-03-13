# syntax=docker/dockerfile:1

# === Build stage ===
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# 1. Copy Gradle infrastructure only (cached layer)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties .editorconfig ./
COPY config/detekt config/detekt
COPY common/build.gradle.kts common/build.gradle.kts
COPY engine/build.gradle.kts engine/build.gradle.kts
COPY gateway/build.gradle.kts gateway/build.gradle.kts
COPY cli/build.gradle.kts cli/build.gradle.kts

# 2. Download dependencies (cache-mounted, survives source code changes)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gateway:dependencies :common:dependencies --no-daemon || true

# 3. Copy source code
COPY common/src common/src
COPY gateway/src gateway/src

# 4. Build fat JAR (no tests)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gateway:shadowJar -x test --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 10001 klaw && adduser -u 10001 -G klaw -D klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
    && chown -R klaw:klaw /home/klaw

WORKDIR /app
COPY --from=builder /build/gateway/build/libs/*-all.jar klaw-gateway.jar
USER klaw
ENTRYPOINT ["java", "-Xms32m", "-Xmx128m", "-Dlogback.configurationFile=logback-docker.xml", "-jar", "/app/klaw-gateway.jar"]
