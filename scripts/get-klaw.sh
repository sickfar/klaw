#!/usr/bin/env bash
set -euo pipefail

if ! command -v docker &>/dev/null; then
  echo "Docker is required but not found. Install Docker first: https://docs.docker.com/get-docker/"
  exit 1
fi

IMAGE="ghcr.io/sickfar/klaw-cli:latest"
VOLUMES="-v /var/run/docker.sock:/var/run/docker.sock \
  -v klaw-config:/root/.config/klaw \
  -v klaw-state:/root/.local/state/klaw \
  -v klaw-data:/root/.local/share/klaw \
  -v klaw-workspace:/workspace"

if [[ "${1:-}" == "install" ]]; then
  INSTALL_DIR="${HOME}/.local/bin"
  mkdir -p "$INSTALL_DIR"
  cat > "$INSTALL_DIR/klaw" <<'WRAPPER'
#!/usr/bin/env bash
exec docker run -it --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v klaw-config:/root/.config/klaw \
  -v klaw-state:/root/.local/state/klaw \
  -v klaw-data:/root/.local/share/klaw \
  -v klaw-workspace:/workspace \
  ghcr.io/sickfar/klaw-cli:latest "$@"
WRAPPER
  chmod +x "$INSTALL_DIR/klaw"
  echo "klaw installed to $INSTALL_DIR/klaw"
  echo "Make sure $INSTALL_DIR is in your PATH."
else
  exec docker run -it --rm $VOLUMES "$IMAGE" init
fi
