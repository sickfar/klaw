# syntax=docker/dockerfile:1

# === Build stage ===
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# 1. Copy Gradle infrastructure only (cached layer - changes rarely)
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties .editorconfig ./
COPY config/detekt config/detekt
COPY common/build.gradle.kts common/build.gradle.kts
COPY engine/build.gradle.kts engine/build.gradle.kts
COPY gateway/build.gradle.kts gateway/build.gradle.kts
COPY cli/build.gradle.kts cli/build.gradle.kts
COPY e2e/build.gradle.kts e2e/build.gradle.kts

# 2. Download dependencies (cache-mounted, survives source code changes)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :engine:dependencies :common:dependencies --no-daemon || true

# 3. Copy source code + docs
COPY common/src common/src
COPY engine/src engine/src
COPY doc doc

# 4. Build fat JAR (no tests)
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :engine:shadowJar -x test --no-daemon

# === Runtime stage ===
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl gnupg gosu \
    && install -m 0755 -d /etc/apt/keyrings \
    && curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /etc/apt/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu jammy stable" \
       > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends docker-ce-cli \
    && apt-get purge -y gnupg \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 10001 klaw && useradd -u 10001 -g klaw -m klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
             /home/klaw/.cache/klaw \
    && chown -R klaw:klaw /home/klaw

WORKDIR /app
COPY --from=builder /build/engine/build/libs/*-all.jar klaw-engine.jar
COPY docker/engine/entrypoint.sh /entrypoint.sh

ENTRYPOINT ["/entrypoint.sh"]
CMD ["java", "-Xms64m", "-Xmx512m", "-Dlogback.configurationFile=logback-docker.xml", "-jar", "/app/klaw-engine.jar"]
