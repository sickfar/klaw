#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ "${1:-}" == "--docker-only" ]]; then
  echo "Building Docker images (self-contained Gradle build inside Docker)..."
  docker build -t ghcr.io/sickfar/klaw-engine:latest -f docker/engine/Dockerfile .
  docker build -t ghcr.io/sickfar/klaw-gateway:latest -f docker/gateway/Dockerfile .
  docker build -t ghcr.io/sickfar/klaw-sandbox:latest -f docker/klaw-sandbox/Dockerfile .
  # CLI needs TARGETARCH for cross-compilation (Kotlin/Native compiler runs on linux/amd64)
  ARCH=$(uname -m)
  if [ "$ARCH" = "aarch64" ] || [ "$ARCH" = "arm64" ]; then
    CLI_ARCH="arm64"
  else
    CLI_ARCH="amd64"
  fi
  docker build --build-arg TARGETARCH="$CLI_ARCH" -t ghcr.io/sickfar/klaw-cli:latest -f docker/cli/Dockerfile .
  echo ""
  echo "Docker images built:"
  docker images --filter reference='ghcr.io/sickfar/klaw-*' --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
else
  echo "Building Klaw artifacts..."
  # For Docker: sqlite-vec must be linux-aarch64; pass -PsqliteVecPlatform=linux-aarch64 to override
  ./gradlew assembleDist "$@"
  echo ""
  echo "Artifacts:"
  ls -lh build/dist/
  echo ""
  echo "To build Docker images, run: $0 --docker-only"
fi
