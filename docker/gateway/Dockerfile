# syntax=docker/dockerfile:1

# === Build stage ===
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy AS builder

# Install Node.js for webui SPA build
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y nodejs && \
    rm -rf /var/lib/apt/lists/*

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
COPY e2e/build.gradle.kts e2e/build.gradle.kts
COPY webui/build.gradle.kts webui/build.gradle.kts

# 2. Copy webui package files for npm install caching
COPY webui/package.json webui/package-lock.json webui/

# 3. Download dependencies (cache-mounted, survives source code changes)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :gateway:dependencies :common:dependencies --no-daemon || true

# 4. Copy source code
COPY common/src common/src
COPY gateway/src gateway/src
COPY webui/app webui/app
COPY webui/nuxt.config.ts webui/nuxt.config.ts
COPY webui/tsconfig.json webui/tsconfig.json

# 5. Build fat JAR (no tests) — includes webui SPA
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
