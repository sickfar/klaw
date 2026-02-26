#!/usr/bin/env bash
# Installs systemd user service units on Raspberry Pi.
# Run this on the Pi AFTER deploying JARs and CLI binary.
# XDG directory creation is handled by `klaw init` — not this script.
# Assumes the repo (or at least deploy/) is present on the Pi.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "Installing Klaw systemd services..."

mkdir -p ~/.config/systemd/user
cp "$SCRIPT_DIR/deploy/klaw-gateway.service" ~/.config/systemd/user/
cp "$SCRIPT_DIR/deploy/klaw-engine.service"  ~/.config/systemd/user/

systemctl --user daemon-reload
systemctl --user enable klaw-gateway klaw-engine

echo ""
echo "systemd services installed and enabled."
echo "Next: run 'klaw init' to create data directories and config templates,"
echo "then: systemctl --user start klaw-engine klaw-gateway"
