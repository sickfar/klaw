#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Building Klaw artifacts..."
./gradlew assembleDist "$@"
echo ""
echo "Artifacts:"
ls -lh build/dist/

if command -v docker &>/dev/null; then
  echo ""
  echo "Building Docker images..."
  docker build -t ghcr.io/sickfar/klaw-engine:latest -f docker/engine/Dockerfile .
  docker build -t ghcr.io/sickfar/klaw-gateway:latest -f docker/gateway/Dockerfile .
  if [ -f build/dist/klaw-linuxX64 ]; then
    docker build -t ghcr.io/sickfar/klaw-cli:latest -f docker/cli/Dockerfile .
  else
    echo "Skipping CLI image (build/dist/klaw-linuxX64 not found — build on Linux to include)"
  fi
  echo ""
  echo "Docker images built:"
  docker images --filter reference='ghcr.io/sickfar/klaw-*' --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
fi
