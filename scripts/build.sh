#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
echo "Building Klaw artifacts..."
./gradlew assembleDist "$@"
echo ""
echo "Artifacts:"
ls -lh build/dist/

if command -v docker &>/dev/null; then
  # Note: Docker images require Linux binaries in build/dist/.
  # On macOS, assembleDist only produces macOS CLI binaries — the cli image build will be skipped
  # by Docker if build/dist/klaw-linuxX64 is missing (the cli Dockerfile COPY will fail).
  # Run on Linux or cross-compile to produce all binaries before building all images.
  echo ""
  echo "Building Docker images (engine + gateway)..."
  docker compose build engine gateway
fi
