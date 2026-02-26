#!/usr/bin/env bash
# Deploy built artifacts to Raspberry Pi and restart services.
# Usage: ./scripts/deploy.sh [PI_HOST] [PI_USER]
# Env vars: KLAW_PI_HOST (default: sickfar-pi.local), KLAW_PI_USER (default: current user)
set -euo pipefail
cd "$(dirname "$0")/.."

PI_HOST="${KLAW_PI_HOST:-sickfar-pi.local}"
PI_USER="${KLAW_PI_USER:-$(whoami)}"
REMOTE_BIN=".local/share/klaw/bin"

# Resolve each JAR specifically to avoid glob expansion issues
GATEWAY_JAR=$(ls build/dist/klaw-gateway-*.jar 2>/dev/null | head -1) || true
ENGINE_JAR=$(ls build/dist/klaw-engine-*.jar 2>/dev/null | head -1) || true

if [[ -z "$GATEWAY_JAR" || -z "$ENGINE_JAR" ]]; then
  echo "No artifacts found in build/dist/. Run ./scripts/build.sh first."
  exit 1
fi

echo "Deploying to $PI_USER@$PI_HOST..."

# Create remote bin dir
ssh "$PI_USER@$PI_HOST" "mkdir -p ~/$REMOTE_BIN"

# Stop services (gracefully ignore if not running)
ssh "$PI_USER@$PI_HOST" "systemctl --user stop klaw-gateway klaw-engine 2>/dev/null || true"

# Upload JARs
rsync -av --progress "$GATEWAY_JAR" "$PI_USER@$PI_HOST:~/$REMOTE_BIN/klaw-gateway.jar"
rsync -av --progress "$ENGINE_JAR"  "$PI_USER@$PI_HOST:~/$REMOTE_BIN/klaw-engine.jar"

# Upload CLI binary for Pi (linuxArm64) to user-writable location
if [[ -f build/dist/klaw-linuxArm64 ]]; then
  rsync -av --progress build/dist/klaw-linuxArm64 "$PI_USER@$PI_HOST:~/.local/bin/klaw"
  ssh "$PI_USER@$PI_HOST" "chmod +x ~/.local/bin/klaw"
fi

# Restart services
ssh "$PI_USER@$PI_HOST" "systemctl --user start klaw-gateway klaw-engine"
echo "Done. Check status with: ssh $PI_USER@$PI_HOST 'systemctl --user status klaw-gateway klaw-engine'"
