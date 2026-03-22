# syntax=docker/dockerfile:1

# === Build stage ===
# Kotlin/Native compiler is only available for linux/x64 hosts,
# so the build stage must always run on amd64 regardless of the target platform.
FROM --platform=linux/amd64 eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

ENV GRADLE_OPTS="-Xmx2g"

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
    ./gradlew :cli:dependencies :common:dependencies --no-daemon || true

# 3. Copy source code
COPY common/src common/src
COPY cli/src cli/src

# 4. Build native Linux binaries (no tests)
#    TARGETARCH is set by Docker buildx: "amd64" or "arm64"
ARG TARGETARCH=amd64
RUN --mount=type=cache,target=/root/.gradle \
    if [ "$TARGETARCH" = "arm64" ]; then \
        GRADLE_TARGET=":cli:linkReleaseExecutableLinuxArm64"; \
    else \
        GRADLE_TARGET=":cli:linkReleaseExecutableLinuxX64"; \
    fi && \
    ./gradlew $GRADLE_TARGET -x test --no-daemon

# === Runtime stage ===
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    docker.io \
  && rm -rf /var/lib/apt/lists/*

RUN groupadd -g 10001 klaw && useradd -u 10001 -g klaw -m klaw

RUN mkdir -p /home/klaw/.local/state/klaw/run \
             /home/klaw/.local/share/klaw \
             /home/klaw/.config/klaw \
    && chown -R klaw:klaw /home/klaw

COPY docker/compose/docker-compose.prod.json /app/docker-compose.json

# Copy the binary for the target architecture
ARG TARGETARCH=amd64
RUN --mount=from=builder,source=/build/cli/build/bin,target=/cli-bins \
    if [ "$TARGETARCH" = "arm64" ]; then \
        cp /cli-bins/linuxArm64/releaseExecutable/klaw.kexe /usr/local/bin/klaw; \
    else \
        cp /cli-bins/linuxX64/releaseExecutable/klaw.kexe /usr/local/bin/klaw; \
    fi && \
    chmod +x /usr/local/bin/klaw

USER klaw
ENTRYPOINT ["klaw"]
