#!/usr/bin/env bash
set -euo pipefail

OWNER="sickfar"
REPO="klaw"
INSTALL_DIR="${HOME}/.local/share/klaw/bin"
SYMLINK_DIR="${HOME}/.local/bin"
API_URL="https://api.github.com/repos/${OWNER}/${REPO}/releases/latest"

# Detect platform
OS="$(uname -s)"
ARCH="$(uname -m)"
case "${OS}-${ARCH}" in
  Linux-aarch64|Linux-arm64) BINARY="klaw-linuxArm64" ;;
  Linux-x86_64)              BINARY="klaw-linuxX64"   ;;
  Darwin-arm64)              BINARY="klaw-macosArm64"  ;;
  Darwin-x86_64)             BINARY="klaw-macosX64"    ;;
  *) echo "Unsupported platform: ${OS}-${ARCH}"; exit 1 ;;
esac

# Check Java 21+
if ! command -v java &>/dev/null; then
  echo "Java 21+ required. Install via your package manager (e.g. sudo apt install openjdk-21-jre)"
  exit 1
fi
JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*version "\([^"]*\)".*/\1/' | cut -d. -f1 || echo "0")
if [[ "${JAVA_VER}" -lt 21 ]]; then
  echo "Java 21+ required (found Java ${JAVA_VER})"
  exit 1
fi

echo "Fetching latest release info..."
RELEASE=$(curl -sSL "${API_URL}")
VERSION=$(echo "$RELEASE" | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
BASE="https://github.com/${OWNER}/${REPO}/releases/download/${VERSION}"

mkdir -p "${INSTALL_DIR}" "${SYMLINK_DIR}"

echo "Downloading klaw ${VERSION} for ${OS}/${ARCH}..."
curl -sSL -o "${INSTALL_DIR}/klaw" "${BASE}/${BINARY}"
chmod +x "${INSTALL_DIR}/klaw"

ENGINE_NAME=$(echo "$RELEASE" | grep -o 'klaw-engine-[^"]*\.jar' | head -1)
GATEWAY_NAME=$(echo "$RELEASE" | grep -o 'klaw-gateway-[^"]*\.jar' | head -1)
curl -sSL -o "${INSTALL_DIR}/klaw-engine.jar" "${BASE}/${ENGINE_NAME}"
curl -sSL -o "${INSTALL_DIR}/klaw-gateway.jar" "${BASE}/${GATEWAY_NAME}"

# Create wrapper scripts
cat > "${INSTALL_DIR}/klaw-engine" <<WRAPPER
#!/usr/bin/env bash
exec java -Xms64m -Xmx512m -jar "${INSTALL_DIR}/klaw-engine.jar" "\$@"
WRAPPER
chmod +x "${INSTALL_DIR}/klaw-engine"

cat > "${INSTALL_DIR}/klaw-gateway" <<WRAPPER
#!/usr/bin/env bash
exec java -jar "${INSTALL_DIR}/klaw-gateway.jar" "\$@"
WRAPPER
chmod +x "${INSTALL_DIR}/klaw-gateway"

# Create symlinks in ~/.local/bin so binaries are on PATH
ln -sf "${INSTALL_DIR}/klaw" "${SYMLINK_DIR}/klaw"
ln -sf "${INSTALL_DIR}/klaw-engine" "${SYMLINK_DIR}/klaw-engine"
ln -sf "${INSTALL_DIR}/klaw-gateway" "${SYMLINK_DIR}/klaw-gateway"

echo ""
echo "klaw ${VERSION} installed."
echo "Make sure ${SYMLINK_DIR} is in your PATH."
echo ""
echo "Starting setup wizard..."
exec "${INSTALL_DIR}/klaw" init
